/* PhoneXR external camera receiver. PeerJS WebRTC data only, no video transfer.
   ArUco/POSIT pose is approximate until camera intrinsics and marker mounting are calibrated. */
(function(){
'use strict';
const PEERJS_URL='https://cdn.jsdelivr.net/npm/peerjs@1.5.5/dist/peerjs.min.js';
let loading=null;
function ensurePeer(){
 if(typeof window.Peer==='function')return Promise.resolve(window.Peer);
 if(loading)return loading;
 loading=new Promise((resolve,reject)=>{
  const script=document.createElement('script');
  script.src=PEERJS_URL;
  script.onload=()=>typeof window.Peer==='function'?resolve(window.Peer):reject(Error('PeerJS не запустился'));
  script.onerror=()=>reject(Error('Не удалось загрузить PeerJS (проверь интернет)'));
  document.head.appendChild(script);
 });
 return loading;
}
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
function createReceiver(onPose,onStatus){
 let peer=null,conn=null,currentCode='';
 function status(str){try{onStatus(str)}catch(e){console.warn('PhoneXR status callback',e)}}
 function stop(){
  try{conn?.close()}catch{}
  try{peer?.destroy()}catch{}
  conn=null;peer=null;currentCode='';
 }
 async function start(){
  stop();status('Загружаю WebRTC...');
  const Peer=await ensurePeer();
  const token=code(),id='phonexr-'+token;
  const p=new Peer(id,{debug:1});
  peer=p;currentCode=token;
  p.on('open',()=>{if(p===peer)status('Готово. Введи код на Redmi 9.')});
  p.on('connection',c=>{
   if(p!==peer){c.close();return}
   if(conn){try{conn.close()}catch{}}
   conn=c;status('Соединяюсь с Redmi 9...');
   c.on('open',()=>{if(conn===c)status('✅ Redmi 9 подключён. Жду метку №347.')});
   c.on('data',data=>{if(conn!==c)return;
    if(validPose(data)||data?.type==='lost'||data?.type==='hello'){
     try{onPose(data)}catch(e){console.error('External pose callback:',e)}
    }
   });
   c.on('close',()=>{if(conn===c){conn=null;status('Redmi 9 отключился. Подключи заново.');onPose({type:'lost'})}});
   c.on('error',e=>status('Ошибка соединения: '+(e?.message||e)));
  });
  p.on('error',e=>{if(p===peer)status('Ошибка связи: '+(e?.message||e))});
  p.on('disconnected',()=>{if(p===peer)status('Потеряна связь с сервером сопряжения')});
  return token;
 }
 return {start,stop,getCode:()=>currentCode,isConnected:()=>!!conn?.open};
}
window.ExternalTracking={createReceiver,anchor,project,validPose};
})();