// Prepare portable release materials from reviewed screenshots and copy.
// Usage: node tools/promo/prepare.mjs 2.6.3
import fs from 'node:fs';
import path from 'node:path';
const version = process.argv[2];
if (!/^\d+\.\d+\.\d+$/.test(version || '')) throw new Error('Pass a version such as 2.6.3');
const root = process.cwd();
const source = path.join(root, 'docs/promo', version);
const out = path.join(root, 'output', `yanwai-${version}`);
fs.mkdirSync(path.join(out, 'images'), {recursive:true});
fs.mkdirSync(path.join(out, 'cards'), {recursive:true});
fs.mkdirSync(path.join(out, 'audio'), {recursive:true});
const scenes = JSON.parse(fs.readFileSync(path.join(source, 'storyboard.json'), 'utf8'));
const esc = s => s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
for (const file of fs.readdirSync(path.join(root, 'docs/images', version))) {
  fs.copyFileSync(path.join(root, 'docs/images', version, file), path.join(out, 'images', file));
}
const css = `*{box-sizing:border-box}body{margin:0;background:#c4cdc7;font-family:'Microsoft YaHei',sans-serif;color:#173d34}.scene{position:relative;width:1080px;height:1920px;overflow:hidden;background:#f3f5ee;padding:68px 74px;margin:0 auto 30px}.scene:nth-child(even){background:#e4eee7}.brand{display:flex;align-items:center;gap:18px;font-size:34px;font-weight:700}.logo{width:64px;height:64px;border-radius:17px}.version{margin-left:auto;font:24px 'Microsoft YaHei';border:1px solid #adc0b4;border-radius:40px;padding:12px 22px}.eyebrow{font-size:26px;letter-spacing:3px;color:#537064;margin-top:68px}.title{font-size:80px;line-height:1.23;letter-spacing:-2px;font-weight:700;margin:25px 0 22px;white-space:pre-line}.description{font-size:31px;line-height:1.6;color:#537064;white-space:pre-line}.phone{position:absolute;top:594px;left:272px;width:536px;height:1167px;border:7px solid #294d43;border-radius:35px;overflow:hidden;box-shadow:0 22px 42px #173d3418;background:#101916}.phone img{width:100%;height:100%;object-fit:contain;display:block}.marker{position:absolute;top:650px;left:72px;font-size:23px;color:#658273;writing-mode:vertical-rl;letter-spacing:8px}.number{position:absolute;right:74px;top:628px;font-size:70px;font-weight:300;color:#9eb7a7}.subtitle{position:absolute;left:55px;right:55px;bottom:81px;text-align:center;font-size:28px;font-weight:600}.footer{position:absolute;left:74px;right:74px;bottom:33px;display:flex;justify-content:space-between;color:#64776c;font-size:18px}.line{position:absolute;height:1px;background:#b8c9be;left:74px;right:74px;bottom:132px}.scene:first-child .title{font-size:78px}.scene:last-child .eyebrow{color:#20685c}`;
const logo = `data:image/png;base64,${fs.readFileSync(path.join(root,'app/src/main/res/drawable-nodpi/yanwai_logo.png')).toString('base64')}`;
const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>言外 ${version} 宣传图</title><style>${css}</style><body>${scenes.map((s,i)=>`<section class="scene" id="scene-${i}" aria-label="${esc(s.title.replaceAll('\n',''))}"><div class="brand"><img class="logo" src="${logo}" alt="言外标志">言外<span class="version">v${version}</span></div><div class="eyebrow">${esc(s.eyebrow)}</div><h1 class="title">${esc(s.title)}</h1><div class="description">${esc(s.description)}</div><div class="marker">真机页面 · 看图上手</div><div class="number">0${i+1}</div><div class="phone"><img src="images/${s.image}" alt="${esc(s.note)}"></div><div class="line"></div><div class="subtitle">${esc(s.subtitle)}</div><div class="footer"><span>${esc(s.note)}</span><span>言外 · ${i+1} / ${scenes.length}</span></div></section>`).join('')}</body></html>`;
fs.writeFileSync(path.join(out, 'cards.html'), html);
fs.copyFileSync(path.join(source, 'storyboard.json'), path.join(out, 'storyboard.json'));
const copy = fs.readFileSync(path.join(source,'copy.md'),'utf8').replaceAll('\r\n','\n');
fs.writeFileSync(path.join(out,'朋友圈文案.txt'),copy.split('## 朋友圈\n\n')[1].split('\n## 图文文章')[0].trim());
const article = copy.split('## 图文文章\n\n')[1].split('\n## 发布搭配')[0].trim().replaceAll(`../../images/${version}/`, 'images/');
fs.writeFileSync(path.join(out,'图文文章.md'), article);
const release = fs.readFileSync(path.join(root,'docs/releases',`${version}.md`),'utf8').replaceAll('\r\n','\n');
fs.writeFileSync(path.join(out,'GitHub-Release.txt'),release.replace(/## 实机页面[\s\S]*?(?=## 安装与升级)/, ''));
fs.writeFileSync(path.join(out,'Release-图文版.md'),release.replaceAll(`../images/${version}/`,'images/'));
const paragraphs = article.split('\n').map(line => {
  if(line.startsWith('![')){const m=line.match(/!\[(.*?)\]\((.*?)\)/);return `<figure><img src="${esc(m[2])}" alt="${esc(m[1])}"><figcaption>${esc(m[1])}</figcaption></figure>`;}
  if(line.startsWith('### '))return `<h2>${esc(line.slice(4))}</h2>`;
  if(line.startsWith('# '))return `<h1>${esc(line.slice(2))}</h1>`;
  return line ? `<p>${esc(line)}</p>` : '';
}).join('\n');
fs.writeFileSync(path.join(out,'index.html'),`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>言外 ${version} 更新素材</title><style>body{margin:0;background:#f3f5ee;color:#173d34;font:18px/1.9 'Microsoft YaHei',sans-serif}main{max-width:880px;padding:45px 24px;margin:auto}h1{font-size:42px;line-height:1.3}h2{font-size:28px;margin-top:64px}a{color:#20685c}nav{display:flex;gap:12px;flex-wrap:wrap;margin:28px 0}nav a{padding:8px 16px;border:1px solid #acc5b7;border-radius:8px;text-decoration:none}video{display:block;max-height:760px;width:100%;background:#e4eee7}figure{text-align:center;margin:25px 0}figure img{max-width:100%;width:360px;border-radius:16px}figcaption{color:#65756c;font-size:14px}.label{color:#64776c}.gallery{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.gallery img{width:100%;height:auto}.caption{font-size:14px}pre{white-space:pre-wrap;font:inherit;padding:22px;background:#e4eee7;border-radius:12px}hr{border:0;border-top:1px solid #c4d3c8;margin:50px 0}</style><main><div class="label">言外 · v${version} · 本地交付</div><h1>更新素材，都在这里。</h1><nav><a href="yanwai-${version}-debug.apk" download>安装包 APK</a><a href="yanwai-${version}-promo.mp4" download>宣传视频 MP4</a><a href="GitHub-Release.txt">Release 文案</a><a href="朋友圈文案.txt">朋友圈文案</a><a href="SHA256SUMS.txt">文件校验</a></nav><video controls preload="metadata" poster="cards/01-cover.png" src="yanwai-${version}-promo.mp4"></video><p class="caption">实机截图编排 · 本地合成旁白 · 无背景音乐。未演示模型生成结果。</p><h2>六张配图</h2><div class="gallery">${scenes.map(s=>`<a href="cards/${s.key}.png"><img src="cards/${s.key}.png" alt="${esc(s.title.replaceAll('\n',''))}"></a>`).join('')}</div><h2>朋友圈文案</h2><pre>${esc(fs.readFileSync(path.join(out,'朋友圈文案.txt'),'utf8'))}</pre><hr>${paragraphs}</main></html>`);
console.log(out);
