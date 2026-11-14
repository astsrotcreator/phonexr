// PhoneXR Visual Anchor. 2D camera feature flow, not 6DoF/SLAM.
const W=128,H=96,R=3,S=12,M=16;
let cvs,ctx,prev=null,pts=[],count=0;
function frameGray(bitmap){
 if(!cvs){cvs=new OffscreenCanvas(W,H);ctx=cvs.getContext('2d',{willReadFrequently:true});if(!ctx)throw Error('Canvas unavailable')}
 ctx.drawImage(bitmap,0,0,W,H);
 const rgba=ctx.getImageData(0,0,W,H).data,out=new Uint8Array(W*H);
 for(let i=0,j=0;i<out.length;i++,j+=4)out[i]=(rgba[j]*77+rgba[j+1]*150+rgba[j+2]*29)>>8;
 return out;
}
function score(a,x,y){
 let xx=0,yy=0,xy=0;
 for(let v=-2;v<=2;v+=2)for(let u=-2;u<=2;u+=2){
  const i=(y+v)*W+x+u,dx=a[i+1]-a[i-1],dy=a[i+W]-a[i-W];
  xx+=dx*dx;yy+=dy*dy;xy+=dx*dy;
 }
 return Math.max(0,(xx+yy-Math.sqrt((xx-yy)**2+4*xy*xy))/2);
}
function detect(a,old=[]){
 const c=[];
 for(let y=M;y<H-M;y+=7)for(let x=M;x<W-M;x+=7){
  const s=score(a,x,y);if(s>550)c.push({x,y,s});
 }
 c.sort((a,b)=>b.s-a.s);const pts=old.map(p=>({x:p.x,y:p.y}));
 for(const p of c){if(pts.length>=18)break;
  if(pts.every(q=>(p.x-q.x)**2+(p.y-q.y)**2>121))pts.push({x:p.x,y:p.y});
 }
 return pts;
}
function sad(a,b,x,y,xx,yy){
 let sum=0;for(let v=-R;v<=R;v++){
  const i0=(y+v)*W+x-R,i1=(yy+v)*W+xx-R;
  for(let k=0;k<7;k++)sum+=Math.abs(a[i0+k]-b[i1+k]);
 }
 return sum;
}
function match(a,b,p){
 let best=1e9,bx=p.x,by=p.y;
 for(let v=-S;v<=S;v+=2)for(let u=-S;u<=S;u+=2){
  const x=p.x+u,y=p.y+v;
  if(x<M||x>=W-M||y<M||y>=H-M)continue;
  const e=sad(a,b,p.x,p.y,x,y);if(e<best){best=e;bx=x;by=y}
 }
 for(let v=-1;v<=1;v++)for(let u=-1;u<=1;u++){
  const x=bx+u,y=by+v;
  if(x<M||x>=W-M||y<M||y>=H-M)continue;
  const e=sad(a,b,p.x,p.y,x,y);if(e<best){best=e;bx=x;by=y}
 }
 return best/49>42?null:{x:bx,y:by,dx:bx-p.x,dy:by-p.y};
}
function median(a){a.sort((a,b)=>a-b);return a[Math.floor(a.length/2)]??0}
function process(a){
 if(!prev){prev=a;pts=detect(a);return {dx:0,dy:0,quality:0,points:pts.length,status:'init'}}
 const found=[];for(const p of pts){const f=match(prev,a,p);if(f)found.push(f)}
 prev=a;
 if(found.length<5){pts=detect(a);return {dx:0,dy:0,quality:0,points:pts.length,status:'reacquire'}}
 const dx=median(found.map(p=>p.dx)),dy=median(found.map(p=>p.dy));
 const good=found.filter(p=>Math.hypot(p.dx-dx,p.dy-dy)<3.5);
 pts=good.map(p=>({x:p.x,y:p.y}));
 if(good.length<5||good.length/found.length<.53||Math.hypot(dx,dy)>14){
  pts=detect(a,pts);return {dx:0,dy:0,quality:0,points:pts.length,status:'unstable'};
 }
 if(pts.length<13)pts=detect(a,pts);
 return {dx,dy,quality:good.length/found.length,points:pts.length,status:'tracking'};
}
self.onmessage=({data})=>{
 if(data.type==='reset'){prev=null;pts=[];count=0;self.postMessage({type:'reset'});return}
 if(data.type==='frame'){
  const bitmap=data.bitmap;
  try{const now=performance.now(),flow=process(frameGray(bitmap));count++;
   self.postMessage({type:'flow',...flow,processingMs:Math.round(performance.now()-now),sequence:count});
  }catch(e){self.postMessage({type:'error',message:String(e?.message||e)})}
  finally{bitmap?.close?.()}
 }
};