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

/*
 * Native MQTT 3.1.1 over secure WebSocket — no external JS dependencies.
 * Both public brokers run in parallel. No server can read payloads: AES-GCM
 * with a key derived from a short-lived shared code.
 * This is an experimental/demo channel, not an authenticated service.
 */
const PX_BROKERS=[
 {name:'EMQX',url:'wss://broker.emqx.io:8084/mqtt'},
 {name:'Mosquitto',url:'wss://test.mosquitto.org:8081'},
 {name:'Eclipse443',url:'wss://mqtt.eclipseprojects.io:443/mqtt'}
];
const encoder=new TextEncoder(),decoder=new TextDecoder();
function hex(bytes){return [...bytes].map(n=>n.toString(16).padStart(2,'0')).join('')}
function freshCode(){return hex(crypto.getRandomValues(new Uint8Array(6)))}
function normalizeCode(v){
 const code=String(v||'').trim().toLowerCase().replace(/[\s-]/g,'');
 if(!/^[0-9a-f]{12}$/.test(code))throw Error('Нужен код из 12 символов: цифры 0–9 и буквы A–F');
 return code;
}
function base64(bytes){
 let s='';for(const n of bytes)s+=String.fromCharCode(n);
 return btoa(s);
}
function unbase64(value){
 const raw=atob(value),out=new Uint8Array(raw.length);
 for(let i=0;i<raw.length;i++)out[i]=raw.charCodeAt(i);
 return out;
}
async function channel(code){
 const topicBits=await crypto.subtle.digest('SHA-256',encoder.encode('phonexr-v14-mqtt-topic:'+code));
 const keyBits=await crypto.subtle.digest('SHA-256',encoder.encode('phonexr-v14-mqtt-key:'+code));
 return {
  topic:'phonexr/v14/'+hex(new Uint8Array(topicBits)).slice(0,26),
  key:await crypto.subtle.importKey('raw',keyBits,'AES-GCM',false,['encrypt','decrypt'])
 };
}
async function encryptMessage(message,key){
 const nonce=crypto.getRandomValues(new Uint8Array(12));
 const cipher=await crypto.subtle.encrypt({name:'AES-GCM',iv:nonce},key,encoder.encode(JSON.stringify(message)));
 return JSON.stringify({v:1,n:base64(nonce),d:base64(new Uint8Array(cipher))});
}
async function decryptMessage(payload,key){
 const packet=JSON.parse(payload);
 if(packet?.v!==1||typeof packet.n!=='string'||typeof packet.d!=='string')throw Error('Invalid packet');
 const nonce=unbase64(packet.n);
 if(nonce.length!==12)throw Error('Invalid nonce');
 const data=await crypto.subtle.decrypt({name:'AES-GCM',iv:nonce},key,unbase64(packet.d));
 return JSON.parse(decoder.decode(data));
}
function appendBytes(...arr){
 const len=arr.reduce((s,a)=>s+a.length,0),out=new Uint8Array(len);
 let n=0;for(const a of arr){out.set(a,n);n+=a.length}
 return out;
}
function utf8(v){
 const bytes=encoder.encode(v);
 return appendBytes(Uint8Array.of(bytes.length>>8,bytes.length&255),bytes);
}
function remainingLength(n){
 const result=[];
 do{let byte=n%128;n=Math.floor(n/128);if(n>0)byte|=0x80;result.push(byte)}while(n>0);
 return Uint8Array.from(result);
}
function mqttFrame(head,payload){
 return appendBytes(Uint8Array.of(head),remainingLength(payload.length),payload);
}
function connectFrame(clientId){
 return mqttFrame(0x10,appendBytes(
  Uint8Array.of(0,4,77,81,84,84,4,2,0,30),utf8(clientId)
 ));
}
function subscribeFrame(topic){
 return mqttFrame(0x82,appendBytes(Uint8Array.of(0,1),utf8(topic),Uint8Array.of(0)));
}
function publishFrame(topic,payload){
 return mqttFrame(0x30,appendBytes(utf8(topic),encoder.encode(payload)));
}
function parseFrames(data,callback){
 const bytes=new Uint8Array(data);
 let pos=0;
 while(pos<bytes.length){
  const head=bytes[pos++];let size=0,mult=1,byte,steps=0;
  do{
   if(pos>=bytes.length||++steps>4)throw Error('Неверный MQTT заголовок');
   byte=bytes[pos++];
   size+=(byte&127)*mult;mult*=128;
  }while(byte&128);
  if(size>bytes.length-pos)throw Error('Неполный MQTT пакет');
  callback(head,bytes.slice(pos,pos+size));
  pos+=size;
 }
}
function parsePublish(packet){
 if(packet.length<2)throw Error('Malformed PUBLISH');
 const len=(packet[0]<<8)+packet[1];
 if(2+len>packet.length)throw Error('Malformed topic');
 const topic=decoder.decode(packet.slice(2,2+len));
 return {topic,message:decoder.decode(packet.slice(2+len))};
}
function startSockets(role,listenTopic,onPacket,onState){
 const sockets=new Map();
 let stopped=false;
 function state(){if(!stopped)onState([...sockets.values()].map(s=>({name:s.name,ready:s.ready,error:s.error})))}
 function openBroker(broker){
  const entry={name:broker.name,ready:false,error:'Подключаюсь',ws:null,ping:null,retry:null,handshake:null,attempts:0};
  sockets.set(broker.name,entry);
  const dial=()=>{
   if(stopped)return;
   entry.ready=false;entry.error='WebSocket...';entry.attempts++;
   let ws;
   try{ws=new WebSocket(broker.url,'mqtt');ws.binaryType='arraybuffer'}
   catch(err){
    entry.error='WebSocket недоступен: '+String(err?.message||err);
    state();scheduleRetry();return;
   }
   entry.ws=ws;state();
   entry.handshake=setTimeout(()=>{
    if(entry.ws===ws&&!entry.ready){
     entry.error='Тайм-аут WebSocket/MQTT';ws.close();
    }
   },12000);
   ws.onopen=()=>{
    if(stopped||ws!==entry.ws)return;
    entry.error='MQTT CONNECT...';
    ws.send(connectFrame('px15'+role+hex(crypto.getRandomValues(new Uint8Array(5)))));
    state();
   };
   ws.onmessage=({data})=>{
    if(stopped||ws!==entry.ws)return;
    try{
     parseFrames(data,(header,bytes)=>{
      const type=header>>4;
      if(type===2){
       if(bytes.length<2||bytes[1]!==0){
        entry.error='MQTT отказ, код '+(bytes[1]??'?');
        ws.close();return;
       }
       entry.error='Подписываюсь...';
       ws.send(subscribeFrame(listenTopic));
      }else if(type===9){
       if(bytes.length<3||bytes[2]===0x80){entry.error='Подписка отклонена';ws.close();return}
       entry.ready=true;entry.error='';
       clearTimeout(entry.handshake);
       clearInterval(entry.ping);
       entry.ping=setInterval(()=>{
        if(ws.readyState===WebSocket.OPEN)ws.send(Uint8Array.of(0xc0,0x00));
       },13000);
       state();
      }else if(type===3){
       const packet=parsePublish(bytes);
       if(packet.topic===listenTopic)onPacket(packet.message,broker.name);
      }
     });
    }catch(err){entry.error='Ошибка протокола: '+String(err?.message||err);state()}
   };
   ws.onerror=()=>{
    if(entry.ws!==ws||stopped)return;
    entry.error='Сбой WebSocket (возможна блокировка порта)';
    state();
   };
   ws.onclose=()=>{
    if(entry.ws!==ws||stopped)return;
    entry.ready=false;
    clearInterval(entry.ping);clearTimeout(entry.handshake);
    if(entry.error===''||entry.error==='Подписываюсь...'||entry.error==='MQTT CONNECT...')entry.error='Связь потеряна';
    state();scheduleRetry();
   };
  };
  const scheduleRetry=()=>{
   if(stopped)return;
   clearTimeout(entry.retry);
   entry.retry=setTimeout(dial,Math.min(9000,2200+entry.attempts*800));
  };
  dial();
 }
 for(const broker of PX_BROKERS)openBroker(broker);
 function send(message,targetName){
  const bytesByName=new Map();
  for(const [name,entry] of sockets){
   if(targetName&&name!==targetName)continue;
   if(!entry.ready||entry.ws?.readyState!==WebSocket.OPEN)continue;
   try{
    const payload=bytesByName.get(name)||publishFrame(listenTopic.replace(/\/(vr|camera)$/,'')+'/'+(role==='vr'?'camera':'vr'),message);
    entry.ws.send(payload);
   }catch(err){entry.error='Отправка: '+String(err?.message||err);state()}
  }
 }
 function online(){return [...sockets.values()].filter(s=>s.ready).map(s=>s.name)}
 function stop(){
  stopped=true;
  for(const s of sockets.values()){
   clearInterval(s.ping);clearTimeout(s.retry);clearTimeout(s.handshake);
   try{s.ws?.close()}catch{}
  }
  sockets.clear();
 }
 return {send,online,stop};
}
function explainServers(states){
 const online=states.filter(s=>s.ready).map(s=>s.name);
 if(online.length)return '✅ Сервер: '+online.join(' + ')+'.';
 return '⚠️ Сервер недоступен: '+states.map(s=>s.name+' ('+s.error+')').join('; ');
}
function createReceiver(onPose,onStatus){
 let session=null,token='',epoch=0,peerConnected=false,statusOnce='';
 const status=message=>{statusOnce=message;try{onStatus(message)}catch(err){console.warn(err)}};
 function stop(){epoch++;session?.stop();session=null;token='';peerConnected=false}
 function start(){
  stop();
  const id=epoch;
  token=freshCode();
  status('Код создан. Проверяю EMQX, Mosquitto и Eclipse443...');
  Promise.resolve().then(async()=>{
   const ch=await channel(token);
   if(id!==epoch)return;
   session=startSockets('vr',ch.topic+'/vr',async(payload,broker)=>{
    try{
     const data=await decryptMessage(payload,ch.key);
     if(id!==epoch)return;
     if(data?.type==='hello'){
      peerConnected=true;
      status('✅ Redmi 9 подключён через '+broker);
      const ack=await encryptMessage({type:'ack'},ch.key);
      session?.send(ack,broker);
     }
     if(['hello','body','pose','lost'].includes(data?.type))onPose(data);
    }catch(e){console.warn('PhoneXR ignored invalid encrypted packet:',e)}
   },states=>{
    if(id!==epoch)return;
    if(peerConnected)return;
    status(explainServers(states)+(states.some(s=>s.ready)?' Введи код на Redmi 9.':' Проверь интернет.'));
   });
  }).catch(err=>{if(id===epoch)status('❌ Соединение не запустилось: '+String(err?.message||err))});
  return Promise.resolve(token);
 }
 return {start,stop,getCode:()=>token,isConnected:()=>peerConnected&&!!session?.online().length};
}
function createSender(onStatus,trackingMode='markerless'){
 let session=null,epoch=0,peerConnected=false,activeBroker=null,helloTimer=null;
 const status=message=>{try{onStatus(message)}catch(e){console.warn(e)}};
 function stop(){epoch++;clearInterval(helloTimer);helloTimer=null;session?.stop();session=null;peerConnected=false;activeBroker=null}
 async function connect(value){
  stop();const id=epoch;
  const token=normalizeCode(value);
  status('Код принят. Подключаю два MQTT-сервера...');
  const ch=await channel(token);
  if(id!==epoch)return;
  const hello=async()=>{
   if(id!==epoch||peerConnected||!session?.online().length)return;
   const encrypted=await encryptMessage({type:'hello',tracking:trackingMode},ch.key);
   if(id===epoch)session.send(encrypted);
  };
  session=startSockets('cam',ch.topic+'/camera',async(payload,broker)=>{
   try{
    const data=await decryptMessage(payload,ch.key);
    if(id!==epoch)return;
    if(data?.type==='ack'){
     peerConnected=true;activeBroker=broker;
     status('✅ Redmi 13 на связи через '+broker+'. Можно запускать камеру.');
    }
   }catch(e){console.warn('PhoneXR ignored invalid ack:',e)}
  },states=>{
   if(id!==epoch)return;
   if(peerConnected&&session?.online().includes(activeBroker))return;
   if(peerConnected&&!session?.online().includes(activeBroker)){peerConnected=false;activeBroker=null}
   status(explainServers(states)+(states.some(s=>s.ready)?' Ожидаю Redmi 13 и проверяю код.':' Проверь интернет.'));
   if(states.some(s=>s.ready))hello();
  });
  helloTimer=setInterval(()=>{if(id===epoch)hello()},2200);
 }
 function send(packet){
  if(!peerConnected||!session?.online().length)return;
  encryptMessage(packet,channelKey).then(m=>{if(session?.online().length)session.send(m,activeBroker)}).catch(console.warn);
 }
 // The key is held only in this sender instance, never stored in localStorage.
 let channelKey=null;
 const originalConnect=connect;
 async function connectAndSave(value){
  const token=normalizeCode(value);
  const ch=await channel(token);
  channelKey=ch.key;
  return originalConnect(token);
 }
 return {connect:connectAndSave,send,stop,isConnected:()=>peerConnected&&!!session?.online().length};
}
window.ExternalTracking={createReceiver,createSender,anchor,project,validPose};
})();