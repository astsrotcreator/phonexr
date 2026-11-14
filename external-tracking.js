/* PhoneXR external camera receiver. PeerJS WebRTC data only, no video transfer.
   ArUco/POSIT pose is approximate until camera intrinsics and marker mounting are calibrated. */
(function(){
'use strict';
function code(){
 const bytes=new Uint8Array(5);
 crypto.getRandomValues(bytes);
 return [...bytes].map(b=>b.toString(16).padStart(2,'0')).join('');
}
function validPose(msg){
 return msg&&msg.type==='pose'&&msg.id===347&&Array.isArray(msg.t)&&msg.t.length===3&&
  msg.t.every(n=>typeof n==='number'&&Number.isFinite(n)&&Math.abs(n)<25000)&&
  Array.isArray(msg.R)&&msg.R.length===3&&msg.R.every(row=>Array.isArray(row)&&row.length===3&&row.every(n=>typeof n==='number'&&Number.isFinite(n)&&Math.abs(n)<=1.5))&&
  msg.t[2]>30&&msg.t[2]<20000;
}
function vec(R,v){return [R[0][0]*v[0]+R[0][1]*v[1]+R[0][2]*v[2],R[1][0]*v[0]+R[1][1]*v[1]+R[1][2]*v[2],R[2][0]*v[0]+R[2][1]*v[1]+R[2][2]*v[2]]}
function tvec(R,v){return [R[0][0]*v[0]+R[1][0]*v[1]+R[2][0]*v[2],R[0][1]*v[0]+R[1][1]*v[1]+R[2][1]*v[2],R[0][2]*v[0]+R[1][2]*v[1]+R[2][2]*v[2]]}
function anchor(pose,centerX,centerY,dist=1250){
 const fovX=62*Math.PI/180,fovY=45*Math.PI/180;
 const x=(centerX/1000*2-1)*Math.tan(fovX/2)*dist;
 const y=(1-centerY/700*2)*Math.tan(fovY/2)*dist;
 const v=vec(pose.R,[x,y,-dist]);
 return v.map((n,i)=>n+pose.t[i]);
}
function project(pose,world){
 const v=tvec(pose.R,world.map((n,i)=>n-pose.t[i]));
 if(v[2]>-80)return {visible:false,x:0,y:0};
 const fX=1000/(2*Math.tan(62*Math.PI/360)),fY=700/(2*Math.tan(45*Math.PI/360));
 return {visible:true,x:500+v[0]/-v[2]*fX,y:350-v[1]/-v[2]*fY};
}
// MQTT over WSS is used as the default transport. No PeerJS signaling is needed.
const MQTT_URL='wss://broker.emqx.io:8084/mqtt';
const MQTT_CDN=[
 'https://cdn.jsdelivr.net/npm/mqtt@5.10.3/dist/mqtt.min.js',
 'https://unpkg.com/mqtt@5.10.3/dist/mqtt.min.js'
];
let mqttLoading=null;
async function ensureMQTT(){
 if(window.mqtt?.connect)return window.mqtt;
 if(mqttLoading)return mqttLoading;
 mqttLoading=(async()=>{
  let lastError;
  for(const src of MQTT_CDN){
   try{
    await new Promise((resolve,reject)=>{
     const script=document.createElement('script');script.src=src;script.async=true;
     script.onload=resolve;script.onerror=()=>reject(Error('Не загрузилась библиотека MQTT: '+src));
     document.head.appendChild(script);
    });
    if(window.mqtt?.connect)return window.mqtt;
    throw Error('MQTT.js загружен без глобальной функции connect');
   }catch(e){lastError=e}
  }
  throw lastError||Error('MQTT.js недоступен');
 })();
 try{return await mqttLoading}finally{mqttLoading=null}
}
function bytesToHex(bytes){return [...bytes].map(n=>n.toString(16).padStart(2,'0')).join('')}
function freshCode(){
 const random=new Uint8Array(6);crypto.getRandomValues(random);
 return bytesToHex(random);
}
function normalizeCode(value){
 const str=String(value||'').trim().toLowerCase().replace(/[^a-f0-9]/g,'');
 if(!/^[0-9a-f]{12}$/.test(str))throw Error('Код должен состоять из 12 символов (0–9, A–F)');
 return str;
}
function bytesToBase64(bytes){
 let chars='';for(const n of bytes)chars+=String.fromCharCode(n);
 return btoa(chars);
}
function base64ToBytes(str){
 const raw=atob(str),bytes=new Uint8Array(raw.length);
 for(let i=0;i<raw.length;i++)bytes[i]=raw.charCodeAt(i);
 return bytes;
}
const encoder=new TextEncoder(),decoder=new TextDecoder();
async function channelFromCode(code){
 const a=await crypto.subtle.digest('SHA-256',encoder.encode('phonexr-v14-mqtt-topic:'+code));
 const b=await crypto.subtle.digest('SHA-256',encoder.encode('phonexr-v14-mqtt-key:'+code));
 const topic='phonexr/v14/'+bytesToHex(new Uint8Array(a)).slice(0,26);
 const key=await crypto.subtle.importKey('raw',b,'AES-GCM',false,['encrypt','decrypt']);
 return {topic,key};
}
async function seal(packet,key){
 const iv=crypto.getRandomValues(new Uint8Array(12));
 const plaintext=encoder.encode(JSON.stringify(packet));
 const cipher=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv},key,plaintext));
 return JSON.stringify({v:1,n:bytesToBase64(iv),d:bytesToBase64(cipher)});
}
async function unseal(payload,key){
 const data=JSON.parse(decoder.decode(payload));
 if(data?.v!==1||typeof data.n!=='string'||typeof data.d!=='string')throw Error('invalid packet');
 const iv=base64ToBytes(data.n);
 if(iv.length!==12)throw Error('invalid nonce');
 const plaintext=await crypto.subtle.decrypt({name:'AES-GCM',iv},key,base64ToBytes(data.d));
 const obj=JSON.parse(decoder.decode(plaintext));
 if(!obj||typeof obj!=='object')throw Error('invalid message');
 return obj;
}
function makeClient(role){
 return window.mqtt.connect(MQTT_URL,{
  clientId:'px14_'+role+'_'+bytesToHex(crypto.getRandomValues(new Uint8Array(5))),
  protocolVersion:4,clean:true,keepalive:20,
  connectTimeout:12000,reconnectPeriod:2500,resubscribe:true,queueQoSZero:false
 });
}
function publishSecure(client,topic,key,packet){
 if(!client?.connected)return;
 seal(packet,key).then(data=>{
  if(client.connected)client.publish(topic,data,{qos:0,retain:false});
 }).catch(e=>console.warn('PhoneXR encrypted message failed',e));
}
function readableError(err){
 return String(err?.message||err?.type||err||'неизвестная ошибка').slice(0,120);
}
function createReceiver(onPose,onStatus){
 let client=null,code='',channel=null,epoch=0,connectedCamera=false;
 const status=message=>{try{onStatus(message)}catch(e){console.warn('PhoneXR status:',e)}};
 function stop(){
  epoch++;
  try{client?.end(true)}catch{}
  client=null;channel=null;connectedCamera=false;code='';
 }
 async function start(){
  stop();
  const id=epoch,token=freshCode();
  status('Подключаю защищённый канал MQTT...');
  await ensureMQTT();
  const ch=await channelFromCode(token);
  if(id!==epoch)throw Error('Соединение отменено');
  channel=ch;code=token;
  return await new Promise((resolve,reject)=>{
   let settled=false;
   const fail=err=>{
    if(settled)return;settled=true;clearTimeout(timeout);
    status('❌ MQTT: '+readableError(err)+' — попробуй Wi-Fi или другой браузер');
    reject(err instanceof Error?err:Error(readableError(err)));
   };
   const timeout=setTimeout(()=>fail(Error('Сервер MQTT не ответил за 14 секунд')),14000);
   const m=makeClient('vr');client=m;
   m.on('connect',()=>{
    if(id!==epoch)return;
    m.subscribe(ch.topic+'/vr',{qos:0},err=>{
     if(id!==epoch)return;
     if(err){fail(err);return}
     if(!settled){settled=true;clearTimeout(timeout);resolve(token)}
     status('✅ MQTT подключён. Введи код на Redmi 9.');
    });
   });
   m.on('message',async(topic,payload)=>{
    if(id!==epoch||topic!==ch.topic+'/vr')return;
    try{
     const data=await unseal(payload,ch.key);
     if(id!==epoch)return;
     if(data.type==='hello'){
      connectedCamera=true;
      status('✅ Redmi 9 на связи! '+(data.tracking==='markerless'?'Без метки.':'По метке.'));
      publishSecure(m,ch.topic+'/camera',ch.key,{type:'ack'});
     }
     if(data.type==='body'||data.type==='pose'||data.type==='lost'||data.type==='hello')onPose(data);
    }catch(err){console.warn('PhoneXR dropped invalid MQTT packet',err)}
   });
   m.on('error',err=>{if(id!==epoch)return;status('Ошибка MQTT: '+readableError(err));});
   m.on('close',()=>{if(id===epoch&&settled){connectedCamera=false;status('Связь MQTT пропала, пытаюсь переподключиться...');onPose({type:'lost'})}});
   m.on('reconnect',()=>{if(id===epoch)status('Переподключаю MQTT-сервер...')});
  });
 }
 return {start,stop,getCode:()=>code,isConnected:()=>!!client?.connected&&connectedCamera};
}
function createSender(onStatus,trackingMode='markerless'){
 let client=null,channel=null,epoch=0,peerSeen=false,helloTimer=null,token='';
 const status=str=>{try{onStatus(str)}catch(e){console.warn(e)}};
 function stop(){
  ++epoch;clearInterval(helloTimer);helloTimer=null;
  try{client?.end(true)}catch{}
  client=null;channel=null;token='';peerSeen=false;
 }
 async function connect(value){
  stop();const id=epoch;
  token=normalizeCode(value);
  status('Загружаю MQTT...');
  await ensureMQTT();
  const ch=await channelFromCode(token);
  if(id!==epoch)throw Error('Подключение отменено');
  channel=ch;
  return await new Promise((resolve,reject)=>{
   let settled=false;
   const fail=e=>{
    if(settled)return;settled=true;clearTimeout(timeout);
    reject(e instanceof Error?e:Error(readableError(e)));
   };
   const timeout=setTimeout(()=>fail(Error('Нет связи с MQTT-сервером (14 секунд)')),14000);
   const m=makeClient('cam');client=m;
   const hello=()=>publishSecure(m,ch.topic+'/vr',ch.key,{type:'hello',tracking:trackingMode==='aruco'?'aruco':'markerless'});
   m.on('connect',()=>{
    if(id!==epoch)return;
    m.subscribe(ch.topic+'/camera',{qos:0},err=>{
     if(id!==epoch)return;
     if(err){fail(err);return}
     if(!settled){settled=true;clearTimeout(timeout);resolve()}
     status('✅ MQTT подключён. Ищу Redmi 13...');
     hello();
     clearInterval(helloTimer);
     helloTimer=setInterval(()=>{if(!peerSeen&&m.connected)hello()},2200);
    });
   });
   m.on('message',async(topic,payload)=>{
    if(id!==epoch||topic!==ch.topic+'/camera')return;
    try{
     const msg=await unseal(payload,ch.key);
     if(id!==epoch)return;
     if(msg.type==='ack'){
      peerSeen=true;
      clearInterval(helloTimer);helloTimer=null;
      status('✅ Redmi 13 подключён! Координаты передаются.');
     }
    }catch(err){console.warn('Invalid camera ack',err)}
   });
   m.on('error',e=>{if(id===epoch)status('MQTT ошибка: '+readableError(e))});
   m.on('close',()=>{if(id===epoch){peerSeen=false;status('MQTT связь потеряна, переподключаюсь...')}});
   m.on('reconnect',()=>{if(id===epoch)status('Переподключаюсь...')});
  });
 }
 function send(packet){
  if(!peerSeen||!client?.connected||!channel)return;
  publishSecure(client,channel.topic+'/vr',channel.key,packet);
 }
 return {connect,send,stop,isConnected:()=>peerSeen&&!!client?.connected};
}
window.ExternalTracking={createReceiver,createSender,anchor,project,validPose};
})();