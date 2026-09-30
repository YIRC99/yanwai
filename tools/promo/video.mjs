import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
const version = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(version || '')) throw new Error('Pass a version');
const root = path.resolve('output',`yanwai-${version}`);
const scenes=JSON.parse(fs.readFileSync(path.join(root,'storyboard.json'),'utf8'));
const intermediate=path.join(root,'render');
fs.mkdirSync(intermediate,{recursive:true});
function run(tool,args){const r=spawnSync(tool,args,{encoding:'utf8',windowsHide:true});if(r.status!==0)throw new Error(r.stderr||r.error||tool);return r.stdout;}
let elapsed=0; const timings=[];
for(const scene of scenes){
  const audio=path.join(root,'audio',`${scene.key}.wav`);
  const probe=JSON.parse(run('ffprobe',['-v','error','-show_entries','format=duration','-of','json',audio]));
  const duration=Math.ceil((Number(probe.format.duration)+0.8)*30)/30;
  const frames=Math.round(duration*30);
  const filter=`zoompan=z='1+0.012*on/${frames}':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=${frames}:s=1080x1920:fps=30,fade=t=in:st=0:d=0.22,fade=t=out:st=${duration-0.22}:d=0.22,format=yuv420p`;
  run('ffmpeg',['-hide_banner','-loglevel','error','-y','-i',path.join(root,'cards',`${scene.key}.png`),'-i',audio,'-vf',filter,'-af','loudnorm=I=-18:TP=-2:LRA=11,adelay=300,apad','-t',String(duration),'-c:v','libx264','-preset','veryfast','-crf','20','-threads','4','-c:a','aac','-b:a','128k','-ar','44100','-ac','2','-movflags','+faststart',path.join(intermediate,`${scene.key}.mp4`)]);
  timings.push({...scene,start:elapsed,duration});elapsed+=duration;
  console.log(`${scene.key}: ${duration.toFixed(2)} seconds`);
}
fs.writeFileSync(path.join(intermediate,'concat.txt'),scenes.map(s=>`file '${s.key}.mp4'`).join('\n'));
run('ffmpeg',['-hide_banner','-loglevel','error','-y','-f','concat','-safe','0','-i',path.join(intermediate,'concat.txt'),'-c','copy','-movflags','+faststart',path.join(root,`yanwai-${version}-promo.mp4`)]);
const clock=t=>{let ms=Math.round(t*1000);const h=Math.floor(ms/3600000);ms%=3600000;const m=Math.floor(ms/60000);ms%=60000;const s=Math.floor(ms/1000);return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')},${String(ms%1000).padStart(3,'0')}`;};
let sub=1;const srt=[];
for(const scene of timings){const sentences=scene.voice.match(/[^。！？]+[。！？]?/g)||[scene.voice];const length=sentences.reduce((n,s)=>n+s.length,0);let t=scene.start+0.3;const speak=scene.duration-0.8;for(const s of sentences){const next=t+s.length/length*speak;srt.push(`${sub++}\n${clock(t)} --> ${clock(next)}\n${s}\n`);t=next;}}
fs.writeFileSync(path.join(root,'旁白字幕.srt'),srt.join('\n'));
fs.writeFileSync(path.join(root,'timings.json'),JSON.stringify({duration:elapsed,scenes:timings},null,2));
console.log(`Final duration: ${elapsed.toFixed(2)} seconds`);
