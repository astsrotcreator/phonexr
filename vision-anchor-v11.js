// PhoneXR v0.11 Camera AR — fixed-keyframe visual anchors.
// Experimental 2D image tracking, not SLAM, 6DoF, depth sensing or navigation.
const W=160,H=120,M=13,SEARCH=18;
let cvs,ctx,reference=null,anchors=[],dx=0,dy=0,misses=0,frames=0,recovery=null;
function gray(bitmap){
 if(!cvs){cvs=new OffscreenCanvas(W,H);ctx=cvs.getContext('2d',{willReadFrequently:true});if(!ctx)throw Error('OffscreenCanvas unsupported')}
 ctx.drawImage(bitmap,0,0,W,H);
 const rgba=ctx.getImageData(0,0,W,H).data,result=new Uint8Array(W*H);
 for(let i=0,j=0;i<result.length;i++,j+=4)result[i]=(rgba[j]*77+rgba[j+1]*150+rgba[j+2]*29)>>8;
 return result;
}
function corner(a,x,y){
 let xx=0,yy=0,xy=0;
 for(let v=-2;v<=2;v+=2)for(let u=-2;u<=2;u+=2){
  const i=(y+v)*W+x+u,ax=a[i+1]-a[i-1],ay=a[i+W]-a[i-W];
  xx+=ax*ax;yy+=ay*ay;xy+=ax*ay;
 }
 return Math.max(0,(xx+yy-Math.sqrt((xx-yy)**2+4*xy*xy))/2);
}
function choose(a){
 const c=[];
 for(let y=M;y<H-M;y+=6)for(let x=M;x<W-M;x+=6){
  const sc=corner(a,x,y);if(sc>750)c.push({x,y,sc});
 }
 c.sort((a,b)=>b.sc-a.sc);
 const out=[];
 for(const p of c){
  if(out.length>=16)break;
  if(out.every(q=>(q.x-p.x)**2+(q.y-p.y)**2>=130))out.push(p);
 }
 return out;
}
function cost(a,b,x,y,u,v){
 let meanA=0,meanB=0;
 for(let yy=-3;yy<=3;yy+=3)for(let xx=-3;xx<=3;xx+=3){
  meanA+=a[(y+yy)*W+x+xx];meanB+=b[(v+yy)*W+u+xx];
 }
 const brightness=(meanB-meanA)/9;
 let err=0;
 for(let yy=-3;yy<=3;yy++)for(let xx=-3;xx<=3;xx++)
  err+=Math.abs(a[(y+yy)*W+x+xx]-b[(v+yy)*W+u+xx]-brightness);
 return err/49;
}
function median(list){list.sort((a,b)=>a-b);return list[Math.floor(list.length/2)]??0}
function match(current,anchor,guessX,guessY,radius){
 let best=1e9,bx=0,by=0,alternate=1e9;
 const loX=Math.max(M,Math.round(guessX)-radius),hiX=Math.min(W-M-1,Math.round(guessX)+radius);
 const loY=Math.max(M,Math.round(guessY)-radius),hiY=Math.min(H-M-1,Math.round(guessY)+radius);
 for(let y=loY;y<=hiY;y++)for(let x=loX;x<=hiX;x++){
  const e=cost(reference,current,anchor.x,anchor.y,x,y);
  if(e<best){best=e;bx=x;by=y}
 }
 if(best>25)return null;
 for(let y=Math.max(loY,by-12);y<=Math.min(hiY,by+12);y+=2)
  for(let x=Math.max(loX,bx-12);x<=Math.min(hiX,bx+12);x+=2){
   if(Math.hypot(x-bx,y-by)<8)continue;
   const e=cost(reference,current,anchor.x,anchor.y,x,y);
   if(e<alternate)alternate=e;
  }
 if(alternate<best+3)return null;
 return {dx:bx-anchor.x,dy:by-anchor.y,err:best};
}
function consensus(matches){
 if(matches.length<5)return null;
 const mx=median(matches.map(p=>p.dx)),my=median(matches.map(p=>p.dy));
 const good=matches.filter(p=>Math.hypot(p.dx-mx,p.dy-my)<3.6);
 if(good.length<5||good.length/matches.length<.63)return null;
 return {dx:median(good.map(p=>p.dx)),dy:median(good.map(p=>p.dy)),
  quality:good.length/matches.length,points:good.length};
}
function local(current){
 const found=[];
 for(const p of anchors){
  const x=p.x+dx,y=p.y+dy;
  if(x<-SEARCH||x>W+SEARCH||y<-SEARCH||y>H+SEARCH)continue;
  const matchResult=match(current,p,x,y,SEARCH);
  if(matchResult)found.push(matchResult);
 }
 return consensus(found);
}
function translationScore(current,ox,oy){
 const errors=[];
 for(const p of anchors){
  const x=p.x+ox,y=p.y+oy;
  if(x<M||x>=W-M||y<M||y>=H-M)continue;
  const e=cost(reference,current,p.x,p.y,x,y);
  if(e<27)errors.push(e);
 }
 if(errors.length<6)return null;
 errors.sort((a,b)=>a-b);
 const keep=errors.slice(0,Math.min(8,errors.length));
 const score=keep.reduce((s,e)=>s+e,0)/keep.length;
 return score<=18?{dx:ox,dy:oy,score,points:errors.length}:null;
}
function findAgain(current){
 if(anchors.length<6)return null;
 let best=null;
 const check=(x,y)=>{
  const candidate=translationScore(current,x,y);
  if(candidate&&(!best||candidate.score<best.score))best=candidate;
 };
 check(0,0);check(dx,dy);
 for(let y=-H+25;y<=H-25;y+=5)
  for(let x=-W+25;x<=W-25;x+=5)check(x,y);
 if(!best)return null;
 let refined=best;
 for(let y=best.dy-4;y<=best.dy+4;y++)
  for(let x=best.dx-4;x<=best.dx+4;x++){
   const c=translationScore(current,x,y);
   if(c&&c.score<refined.score)refined=c;
  }
 return refined;
}
function process(current){
 frames++;
 if(!reference){
  reference=current;anchors=choose(current);dx=0;dy=0;misses=0;
  return {status:anchors.length>=5?'init':'no-features',visible:anchors.length>=5,
   offsetX:0,offsetY:0,quality:0,points:anchors.length};
 }
 const matchResult=local(current);
 if(matchResult){
  dx=matchResult.dx;dy=matchResult.dy;misses=0;recovery=null;
  return {status:'tracking',visible:true,offsetX:dx,offsetY:dy,
   quality:matchResult.quality,points:matchResult.points};
 }
 misses++;
 if(misses>=2&&(misses===2||frames%3===0)){
  const candidate=findAgain(current);
  if(candidate){
   const same=recovery&&Math.hypot(recovery.dx-candidate.dx,recovery.dy-candidate.dy)<5;
   recovery=same?{...candidate,count:recovery.count+1}:{...candidate,count:1};
   if(recovery.count>=2){
    dx=candidate.dx;dy=candidate.dy;misses=0;recovery=null;
    return {status:'relocalized',visible:true,offsetX:dx,offsetY:dy,quality:1,points:candidate.points};
   }
  }else recovery=null;
 }
 return {status:anchors.length<5?'no-features':recovery?'verifying':'lost',
  visible:misses<2&&anchors.length>=5,offsetX:dx,offsetY:dy,quality:0,points:0};
}
self.onmessage=({data})=>{
 if(data.type==='reset'){
  reference=null;anchors=[];dx=0;dy=0;misses=0;frames=0;recovery=null;
  self.postMessage({type:'reset'});return;
 }
 if(data.type==='frame'){
  const bitmap=data.bitmap;
  try{const start=performance.now(),result=process(gray(bitmap));
   self.postMessage({type:'flow',...result,processingMs:Math.round(performance.now()-start)});
  }catch(e){self.postMessage({type:'error',message:String(e?.message||e)})}
  finally{bitmap?.close?.()}
 }
};