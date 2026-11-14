// PhoneXR markerless tracking — on-device body pose, not headset 6DoF.
let detector=null,lastTimestamp=0;
function visible(p){return !!p&&Number.isFinite(p.x)&&Number.isFinite(p.y)&&(p.visibility===undefined||p.visibility>=.30)}
function midpoint(a,b){return{x:(a.x+b.x)/2,y:(a.y+b.y)/2}}
function derivePose(pts){
 if(!pts||pts.length<25)return null;
 const L=pts[11],R=pts[12],HL=pts[23],HR=pts[24];
 if(!visible(L)||!visible(R))return null;
 const shoulders=midpoint(L,R);
 const width=Math.hypot(L.x-R.x,L.y-R.y);
 if(width<.025)return null;
 const hips=visible(HL)&&visible(HR)?midpoint(HL,HR):null;
 const faceIndices=[0,7,8,2,5];
 const faces=faceIndices.filter(i=>visible(pts[i])&&(pts[i].visibility??1)>.45).map(i=>pts[i]);
 // Head position is approximated when the person is viewed from behind.
 const head=faces.length
  ?{x:faces.reduce((a,p)=>a+p.x,0)/faces.length,y:faces.reduce((a,p)=>a+p.y,0)/faces.length}
  :{x:shoulders.x,y:shoulders.y-width*.66};
 const highQuality=faces.length>=2&&!!hips;
 return{
  x:Math.max(0,Math.min(1,head.x)),
  y:Math.max(0,Math.min(1,head.y)),
  width,
  shoulders:{x:shoulders.x,y:shoulders.y},
  hips:hips?{x:hips.x,y:hips.y}:null,
  // Relative size proxy only. NOT metric distance or accurate headset rotation.
  size:hips?Math.hypot(shoulders.x-hips.x,shoulders.y-hips.y):width*1.9,
  quality:highQuality?'full':faces.length?'head':'shoulders',
  direction:'unknown',
  points:[0,7,8,11,12,13,14,23,24].filter(i=>visible(pts[i])).map(i=>({i,x:pts[i].x,y:pts[i].y}))
 };
}
self.onmessage=async({data})=>{
 if(data.type==='init'){
  try{
   const {PoseLandmarker,FilesetResolver}=await import('https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/vision_bundle.mjs');
   const vision=await FilesetResolver.forVisionTasks('https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/wasm');
   detector=await PoseLandmarker.createFromOptions(vision,{
    baseOptions:{modelAssetPath:'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task',delegate:'CPU'},
    runningMode:'VIDEO',numPoses:1,minPoseDetectionConfidence:.45,minPosePresenceConfidence:.45,minTrackingConfidence:.45,
    outputSegmentationMasks:false
   });
   self.postMessage({type:'ready'});
  }catch(e){self.postMessage({type:'error',message:String(e?.message||e)})}
  return;
 }
 if(data.type==='detect'){
  const bitmap=data.bitmap;
  try{
   if(!detector)throw Error('Pose detector not ready');
   const start=performance.now();
   const timestamp=Math.max(lastTimestamp+1,Number(data.timestamp)||performance.now());
   lastTimestamp=timestamp;
   const result=detector.detectForVideo(bitmap,timestamp);
   const body=derivePose(result.landmarks?.[0]);
   self.postMessage({type:'result',body,processingMs:Math.round(performance.now()-start)});
  }catch(e){self.postMessage({type:'error',message:String(e?.message||e)})}
  finally{bitmap?.close?.()}
 }
};