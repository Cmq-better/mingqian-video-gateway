const endpoints=[
  {id:'health',method:'GET',path:'/open-api/v1/health',title:'服务健康检测',scope:'READ',desc:'验证服务存活、网络和 API Key。'},
  {id:'devices',method:'GET',path:'/open-api/v1/devices',title:'查询设备列表',scope:'READ',desc:'返回脱敏设备、协议、在线状态和通道摘要。'},
  {id:'channels',method:'GET',path:'/open-api/v1/channels',title:'分页查询通道',scope:'READ',desc:'跨设备搜索视频通道，单页最多 100 条。',fields:[['offset','number','起始偏移','0'],['limit','number','每页数量','30'],['q','text','搜索关键词','']]},
  {id:'deviceChannels',method:'GET',path:'/open-api/v1/devices/{deviceId}/channels',title:'查询设备通道',scope:'READ',desc:'返回指定设备的全部视频通道。',fields:[['deviceId','text','设备编码','34020000001320000001']]},
  {id:'platform',method:'GET',path:'/open-api/v1/platform',title:'查询平台状态',scope:'READ',desc:'查询 SIP、媒体服务与平台接入状态。'},
  {id:'snapshotStatus',method:'GET',path:'/open-api/v1/snapshots/status',title:'查询抽帧队列',scope:'READ',desc:'查看排队数、缓存命中、直播复用与任务间隔。'},
  {id:'snapshot',method:'GET',path:'/open-api/v1/devices/{deviceId}/snapshot',title:'排队获取通道快照',scope:'PLAYBACK',desc:'串行抽帧并优先复用同通道直播；返回 image/jpeg。',binary:true,fields:[['deviceId','text','设备编码','34020000001320000001'],['channelId','text','通道编码','34020000001320000001']]},
  {id:'play',method:'POST',path:'/open-api/v1/devices/{deviceId}/play',title:'发起实时点播',scope:'PLAYBACK',desc:'创建受控播放会话并返回 HLS 地址和 Playback Token。',fields:[['deviceId','text','设备编码','34020000001320000001'],['channelId','text','通道编码','34020000001320000001'],['streamType','select','码流','SUB','MAIN|SUB|THIRD']],body:['channelId','streamType']},
  {id:'heartbeat',method:'POST',path:'/open-api/v1/devices/{deviceId}/play/{playbackId}/heartbeat',title:'发送点播心跳',scope:'PLAYBACK',desc:'建议每 10 秒调用，维持当前观看会话。',fields:[['deviceId','text','设备编码','34020000001320000001'],['playbackId','text','点播会话 ID','playback-id']]},
  {id:'stop',method:'POST',path:'/open-api/v1/devices/{deviceId}/play/{playbackId}/stop',title:'停止实时点播',scope:'PLAYBACK',desc:'主动释放摄像机、SIP 与媒体资源。',fields:[['deviceId','text','设备编码','34020000001320000001'],['playbackId','text','点播会话 ID','playback-id']]},
  {id:'ptz',method:'POST',path:'/open-api/v1/devices/{deviceId}/ptz',title:'控制 GB28181 云台',scope:'CONTROL',desc:'发送方向、变焦或停止指令，速度范围 0–255。',fields:[['deviceId','text','设备编码','34020000001320000001'],['channelId','text','通道编码','34020000001320000001'],['action','select','动作','UP','UP|DOWN|LEFT|RIGHT|ZOOM_IN|ZOOM_OUT|STOP'],['speed','number','速度','64']],body:['channelId','action','speed']},
  {id:'media',method:'GET',path:'/api/playbacks/{playbackId}/media/{file}',title:'读取 HLS 媒体',scope:'PLAYBACK',desc:'清单和分片必须同时携带 Bearer Key 与 X-Playback-Token。',binary:true,fields:[['playbackId','text','点播会话 ID','playback-id'],['file','text','媒体文件','index.m3u8'],['playbackToken','password','Playback Token','']]}
];

let selected=endpoints[0],requestLanguage='curl',quickLanguage='curl';
const $=id=>document.getElementById(id);
const escapeHtml=value=>String(value).replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const baseUrl=()=>($('apiBase').value||location.origin).replace(/\/+$/,'');

function renderNavigation(){
  $('endpointNav').innerHTML=endpoints.map(endpoint=>`<a href="#reference" data-nav-endpoint="${endpoint.id}"><span class="method ${endpoint.method.toLowerCase()}">${endpoint.method}</span>${escapeHtml(endpoint.title)}</a>`).join('');
  document.querySelectorAll('[data-nav-endpoint]').forEach(link=>link.addEventListener('click',()=>selectEndpoint(link.dataset.navEndpoint)));
}

function renderEndpoints(filter=''){
  const term=filter.trim().toLowerCase();
  const visible=endpoints.filter(endpoint=>`${endpoint.method} ${endpoint.path} ${endpoint.title} ${endpoint.scope}`.toLowerCase().includes(term));
  $('endpointList').innerHTML=visible.length?visible.map(endpoint=>`<article class="endpoint ${selected.id===endpoint.id?'selected':''}" data-endpoint="${endpoint.id}"><button class="endpoint-button"><span class="verb ${endpoint.method.toLowerCase()}">${endpoint.method}</span><code>${escapeHtml(endpoint.path)}</code><span>${escapeHtml(endpoint.title)}</span></button><div class="endpoint-detail"><p>${escapeHtml(endpoint.desc)}</p><div class="meta-row"><b>${endpoint.scope}</b><b>application/json</b></div></div></article>`).join(''):'<div style="padding:30px;color:#7a8798">没有匹配的接口</div>';
  document.querySelectorAll('[data-endpoint] .endpoint-button').forEach(button=>button.addEventListener('click',()=>selectEndpoint(button.closest('[data-endpoint]').dataset.endpoint)));
}

function selectEndpoint(id){
  selected=endpoints.find(endpoint=>endpoint.id===id)||endpoints[0];
  renderEndpoints($('docSearch').value);
  $('tryTitle').textContent=`${selected.method} · ${selected.title}`;
  $('tryFields').innerHTML=(selected.fields||[]).map(field=>{
    const [name,type,label,value,options]=field;
    if(type==='select')return `<label>${escapeHtml(label)}<select data-field="${name}">${options.split('|').map(option=>`<option ${option===value?'selected':''}>${option}</option>`).join('')}</select></label>`;
    return `<label>${escapeHtml(label)}<input data-field="${name}" type="${type}" value="${escapeHtml(value)}" ${type==='number'?'min="0"':''}></label>`;
  }).join('');
  $('sendRequest').disabled=false;
  $('tryResponse').textContent='—';
  document.querySelectorAll('#tryFields [data-field],#apiBase,#apiKey').forEach(input=>input.addEventListener('input',renderRequestCode));
  renderRequestCode();
}

function requestModel(){
  const values={};document.querySelectorAll('#tryFields [data-field]').forEach(input=>values[input.dataset.field]=input.value);
  let path=selected.path.replace(/\{([^}]+)\}/g,(_,name)=>encodeURIComponent(values[name]||`{${name}}`));
  if(selected.id==='channels'){
    const query=new URLSearchParams();['offset','limit','q'].forEach(name=>{if(values[name]!==''&&values[name]!=null)query.set(name,values[name])});path+=`?${query}`;
  }
  if(selected.id==='snapshot'&&values.channelId)path+=`?channelId=${encodeURIComponent(values.channelId)}`;
  const body={};(selected.body||[]).forEach(name=>body[name]=name==='speed'?Number(values[name]):values[name]);
  return {url:baseUrl()+path,path,values,body:selected.body?body:null,key:$('apiKey').value.trim()};
}

function codeFor(language,model=requestModel()){
  const headers={Authorization:'Bearer '+(model.key||'vhk_your_api_key')};
  if(selected.id==='media')headers['X-Playback-Token']=model.values.playbackToken||'<playbackToken>';
  if(model.body)headers['Content-Type']='application/json';
  if(language==='javascript')return `const response = await fetch('${model.url}', {\n  method: '${selected.method}',\n  headers: ${JSON.stringify(headers,null,2)}${model.body?`,\n  body: JSON.stringify(${JSON.stringify(model.body,null,2)})`:''}\n});\n\nif (!response.ok) throw new Error(\`HTTP \${response.status}\`);\nconst data = await response.${selected.binary?'blob()':'json()'};`;
  if(language==='python')return `import os\nimport requests\n\nresponse = requests.${selected.method.toLowerCase()}(\n    '${model.url}',\n    headers=${pythonObject(headers)}${model.body?`,\n    json=${pythonObject(model.body)}`:''}\n)\nresponse.raise_for_status()\n${selected.binary?'content = response.content':'data = response.json()'}`;
  if(language==='java')return `var client = java.net.http.HttpClient.newHttpClient();\nvar request = java.net.http.HttpRequest.newBuilder()\n    .uri(java.net.URI.create("${model.url}"))\n    .header("Authorization", "Bearer " + apiKey)${model.body?`\n    .header("Content-Type", "application/json")\n    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("${JSON.stringify(model.body).replace(/"/g,'\\"')}"))`:'\n    .GET()'}\n    .build();\nvar response = client.send(request,\n    java.net.http.HttpResponse.BodyHandlers.ofString());`;
  const parts=[`curl --request ${selected.method} '${model.url}'`,`  --header 'Authorization: Bearer ${model.key||'vhk_your_api_key'}'`];
  if(selected.id==='media')parts.push(`  --header 'X-Playback-Token: ${model.values.playbackToken||'<playbackToken>'}'`);
  if(model.body)parts.push(`  --header 'Content-Type: application/json'`,`  --data '${JSON.stringify(model.body)}'`);
  return parts.join(' \\\n');
}

function pythonObject(value){return JSON.stringify(value,null,4).replace(/true/g,'True').replace(/false/g,'False').replace(/null/g,'None')}
function renderRequestCode(){$('requestCode').textContent=codeFor(requestLanguage)}

function quickCode(language){
  const model={url:`${location.origin}/open-api/v1/health`,key:'vhk_your_api_key',values:{},body:null};
  const previous=selected;selected=endpoints[0];const code=codeFor(language,model);selected=previous;return code;
}

document.querySelectorAll('[data-code-group]').forEach(group=>group.querySelectorAll('button').forEach(button=>button.addEventListener('click',()=>{
  group.querySelectorAll('button').forEach(item=>item.classList.toggle('active',item===button));
  if(group.dataset.codeGroup==='quick'){quickLanguage=button.dataset.lang;$('quickCode').textContent=quickCode(quickLanguage)}else{requestLanguage=button.dataset.lang;renderRequestCode()}
})));

async function sendTryRequest(){
  const model=requestModel();if(!model.key){toast('请先填写 API Key');$('apiKey').focus();return}
  const headers={Authorization:`Bearer ${model.key}`};if(selected.id==='media')headers['X-Playback-Token']=model.values.playbackToken||'';if(model.body)headers['Content-Type']='application/json';
  setStatus('SENDING','pending');$('sendRequest').disabled=true;$('tryResponse').textContent='正在发送请求…';
  const started=performance.now();
  try{
    const response=await fetch(model.url,{method:selected.method,headers,body:model.body?JSON.stringify(model.body):undefined});
    const contentType=response.headers.get('content-type')||'';let output;
    if(contentType.includes('application/json'))output=JSON.stringify(await response.json(),null,2);else if(selected.binary){const blob=await response.blob();output=`Binary response\nContent-Type: ${contentType||'application/octet-stream'}\nSize: ${blob.size} bytes`}else output=await response.text();
    $('tryResponse').textContent=`HTTP ${response.status} · ${Math.round(performance.now()-started)} ms\n\n${output}`;setStatus(response.ok?'SUCCESS':`HTTP ${response.status}`,response.ok?'success':'error');
  }catch(error){$('tryResponse').textContent=`请求失败\n\n${error.message}\n\n浏览器跨域调用请检查 OPEN_API_ALLOWED_ORIGINS。`;setStatus('FAILED','error')}
  finally{$('sendRequest').disabled=false}
}

function setStatus(text,kind){$('tryStatus').textContent=text;$('tryStatus').className=kind||''}
function toggleKey(){const input=$('apiKey');input.type=input.type==='password'?'text':'password'}
async function copyText(text){try{await navigator.clipboard.writeText(text);toast('已复制到剪贴板')}catch{toast('复制失败，请手动复制')}}
function copyElement(id){copyText($(id).textContent)}
function toast(message){const node=$('devToast');node.textContent=message;node.classList.add('show');clearTimeout(window.__toastTimer);window.__toastTimer=setTimeout(()=>node.classList.remove('show'),1800)}

async function downloadOpenApi(){
  try{const response=await fetch(`${location.origin}/open-api/v1/spec`);if(!response.ok)throw new Error(`HTTP ${response.status}`);const spec=await response.json();const blob=new Blob([JSON.stringify(spec,null,2)],{type:'application/json'});const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download='mingqian-video-openapi-v1.json';link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000);toast('OpenAPI 规范已下载')}catch(error){toast(`下载失败：${error.message}`)}
}

$('docSearch').addEventListener('input',event=>renderEndpoints(event.target.value));
$('apiBase').value=location.origin;
$('quickCode').textContent=quickCode(quickLanguage);
renderNavigation();renderEndpoints();selectEndpoint('health');

const sections=[...document.querySelectorAll('.dev-main section[id]')];
const observer=new IntersectionObserver(entries=>{const visible=entries.filter(entry=>entry.isIntersecting).sort((a,b)=>b.intersectionRatio-a.intersectionRatio)[0];if(!visible)return;document.querySelectorAll('.dev-sidebar>a').forEach(link=>link.classList.toggle('active',link.getAttribute('href')===`#${visible.target.id}`))},{rootMargin:'-80px 0px -65% 0px',threshold:[0,.2,.6]});
sections.forEach(section=>observer.observe(section));
