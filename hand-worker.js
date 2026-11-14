// PhoneXR hand inference worker — keep synchronous MediaPipe away from VR rendering.
let tracker=null;
self.onmessage=async ({data})=>{
 if(data.type==='init'){
  try{
   const {HandLandmarker,FilesetResolver}=await import('https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/vision_bundle.mjs');
   const vision=await FilesetResolver.forVisionTasks('https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.35/wasm');
   tracker=await HandLandmarker.createFromOptions(vision,{
    baseOptions:{
     modelAssetPath:'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task',
     delegate:'CPU'
    },
    runningMode:'VIDEO',numHands:1,
    minHandDetectionConfidence:.55,
    minHandPresenceConfidence:.5,
    minTrackingConfidence:.5
   });
   self.postMessage({type:'ready'});
  }catch(e){self.postMessage({type:'fatal',message:String(e?.message||e)});}
  return;
 }
 if(data.type==='detect'){
  const bitmap=data.bitmap;
  try{
   if(!tracker)throw Error('Hand tracker not initialized');
   const started=performance.now();
   const result=tracker.detectForVideo(bitmap,data.timestampMs);
   self.postMessage({
    type:'result',landmarks:result.landmarks?.[0]||null,
    inferenceMs:Math.round(performance.now()-started)
   });
  }catch(e){self.postMessage({type:'fatal',message:String(e?.message||e)});}
  finally{bitmap?.close?.();}
 }
};
