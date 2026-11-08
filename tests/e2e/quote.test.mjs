import {BASE,call,login,good,denied,ok,section,finish,sql,sqlFile,DEPLOY_DIR} from './lib.mjs'
import fs from 'node:fs'
section('报价：权限、附件、转换、并发与删除')
const boss=await login('admin','admin123');ok('管理员登录',!!boss)
const tag=Date.now().toString().slice(-8),pw='QuoteTest123'
for(const [userName,nickName] of [['qa'+tag,'报价甲'],['qb'+tag,'报价乙']])ok('创建员工 '+userName,good(await call(boss,'POST','/jiedan/staff',{userName,nickName,password:pw})))
const staff=(await call(boss,'GET','/jiedan/staff/list')).data
const a=staff.find(s=>s.userName==='qa'+tag),b=staff.find(s=>s.userName==='qb'+tag)
const ta=await login(a.userName,pw),tb=await login(b.userName,pw)
await call(boss,'POST','/jiedan/customer',{userName:'qc'+tag,nickName:'报价外部客户',password:pw,ownerId:a.id})
const client=await login('qc'+tag,pw)
for(const token of [null,client])ok('未授权用户不能读取报价',denied(await call(token,'GET','/jiedan/quote/list')))
const before=Number(sql('select count(*) from jiedan_order'))
const form=new FormData();form.append('file',new Blob(['需求附件内容'],{type:'text/plain'}),'需求.txt')
const uploaded=await (await fetch(BASE+'/jiedan/media/upload',{method:'POST',headers:{Authorization:'Bearer '+boss},body:form})).json();ok('真实文件上传',good(uploaded))
const attachment={...uploaded.data,type:'file'}
const make=(extra={})=>call(boss,'POST','/jiedan/quote',{title:'报价测试-'+tag,requirement:'企业官网含移动端',employeeId:a.id,attachments:[attachment],...extra})
for(const extra of [{employeeId:1},{employeeId:99999999},{requirement:'',attachments:[]},{attachments:[{...attachment,url:'javascript:alert(1)'}]}])ok('非法创建被拒绝',denied(await make(extra)))
{const many=await make({title:'多附件报价-'+tag,attachments:Array(15).fill(attachment)});ok('附件张数不限（15 个可创建）',good(many)&&many.data?.attachments?.length===15);if(many.data?.id)ok('多附件报价可删除',good(await call(boss,'DELETE','/jiedan/quote/'+many.data.id,{version:many.data.version})))}
sql("insert into jiedan_setting(setting_key,setting_value) values('quote.defaultEmployeeId','"+a.id+"') on duplicate key update setting_value=values(setting_value)")
ok('默认报价人出现在可选成员里',(await call(boss,'GET','/jiedan/staff/list')).data.find(x=>x.id===a.id)?.defaultQuoteEmployee===true)
const newNamed=(sourceName,extra={})=>call(boss,'POST','/jiedan/quote',{sourceName,requirement:'核对编号',...extra})
const simultaneous=await Promise.all([newNamed('小慧'),newNamed('小慧')])
ok('同一来源并发发送自动顺排且默认选对报价人',simultaneous.every(good)&&simultaneous.map(x=>x.data.quoteNo).sort().join()==='小慧1,小慧2' && simultaneous.every(x=>x.data.title===x.data.quoteNo && x.data.employeeId===a.id && x.data.sentTime===x.data.createTime))
const otherSource=(await newNamed('杨',{employeeId:b.id,title:'自选项目名'})).data
ok('每个人独立编号且可以改选报价人',otherSource.quoteNo==='杨1' && otherSource.employeeId===b.id && otherSource.title==='自选项目名')
const existing=(await newNamed('小慧',{manualNumber:'小慧10'})).data
ok('补录已有单号后继续顺排',existing.quoteNo==='小慧10' && (await newNamed('小慧')).data.quoteNo==='小慧11')
await call(boss,'DELETE','/jiedan/quote/'+existing.id,{version:existing.version})
ok('删除后不重用旧单号，不接受其他来源前缀',denied(await newNamed('小慧',{manualNumber:'10'})) && denied(await newNamed('小慧',{manualNumber:'小松3'})))
ok('预计单号来自服务器',(await call(boss,'GET','/jiedan/quote/numbering')).data.find(x=>x.name==='小慧').next===12)
const numbered=simultaneous[0].data
sql("update jiedan_quote set create_time='2025-04-02 09:14:00',last_sent_time='2025-04-02 09:14:00' where id="+numbered.id)
const editNumbered={...numbered,title:'项目新名',sourceName:'小慧',attachments:[],version:numbered.version}
const savedNo=(await call(boss,'PUT','/jiedan/quote/'+numbered.id,editNumbered)).data
const reinvited=(await call(boss,'PUT','/jiedan/quote/'+numbered.id,{...editNumbered,version:savedNo.version,requestPrice:true})).data
ok('重邀更新邀请时间，改名和重邀都保留发起时间与单号',savedNo.sentTime===savedNo.createTime && reinvited.createTime===savedNo.createTime && reinvited.sentTime>reinvited.createTime && reinvited.quoteNo===numbered.quoteNo)
ok('员工不能发起报价',denied(await call(ta,'POST','/jiedan/quote',{title:'越权',employeeId:a.id,requirement:'x'})))
let q=(await make()).data;ok('管理员创建成功',!!q?.id);if(!q)throw Error('Quote creation failed')
ok('附件可取回',q.attachments?.[0]?.url===attachment.url)
ok('未成交不生成项目',Number(sql('select count(*) from jiedan_order'))===before)
ok('未报价不能转项目',denied(await call(boss,'POST',`/jiedan/quote/${q.id}/convert`,{version:q.version})))
ok('分配员工看见报价',good(await call(ta,'GET','/jiedan/quote/'+q.id)))
ok('其他员工列表隔离',!(await call(tb,'GET','/jiedan/quote/list')).data.some(x=>x.id===q.id))
for(const [method,path,data] of [['GET','',undefined],['PUT','/price',{version:q.version,amount:100}],['PUT','/assign',{version:q.version,employeeId:b.id}],['DELETE','',{version:q.version}],['POST','/convert',{version:q.version}]])ok('其他员工不能操作 '+method+path,denied(await call(tb,method,'/jiedan/quote/'+q.id+path,data)))
ok('客户不能读单个报价',denied(await call(client,'GET','/jiedan/quote/'+q.id)))
ok('管理员不能冒充员工报价',denied(await call(boss,'PUT',`/jiedan/quote/${q.id}/price`,{version:q.version,amount:100})))
for(const amount of [0,-1,'abc',12.345,10000000000,null])ok('无效金额被拒绝 '+amount,denied(await call(ta,'PUT',`/jiedan/quote/${q.id}/price`,{version:q.version,amount})))
ok('缺少版本拒绝',denied(await call(ta,'PUT',`/jiedan/quote/${q.id}/price`,{amount:300})))
const stale=q.version
q=(await call(ta,'PUT',`/jiedan/quote/${q.id}/price`,{version:q.version,amount:4800.25,quoteNote:'包含设计与开发，工期 10 天'})).data
ok('员工报价成功',q.status==='quoted'&&Number(q.amount)===4800.25)
const rename=(token,title,version)=>call(token,'PUT',`/jiedan/quote/${q.id}/title`,{title,version})
ok('协作账号不能改报价标题',denied(await rename(ta,'受限',q.version)))
ok('空白标题被拒绝',denied(await rename(boss,'  ',q.version)))
ok('过期版本不能改名',denied(await rename(boss,'过期',stale)))
q=(await rename(boss,' 已改名的报价单 ',q.version)).data
ok('改名保留原报价及附件',q.title==='已改名的报价单'&&q.status==='quoted'&&Number(q.amount)===4800.25&&q.attachments[0].url===attachment.url)
ok('旧版本不能覆盖',denied(await call(ta,'PUT',`/jiedan/quote/${q.id}/price`,{version:stale,amount:1})))
ok('员工不能删除',denied(await call(ta,'DELETE','/jiedan/quote/'+q.id,{version:q.version})))
ok('员工不能转项目',denied(await call(ta,'POST',`/jiedan/quote/${q.id}/convert`,{version:q.version})))
ok('报价不改变项目数量',Number(sql('select count(*) from jiedan_order'))===before)
ok('有报价员工不能删除',denied(await call(boss,'DELETE','/jiedan/staff/'+a.id)))
const pair=await Promise.all([1,2].map(()=>call(boss,'POST',`/jiedan/quote/${q.id}/convert`,{version:q.version})))
ok('并发转换均可安全返回',pair.every(good));ok('并发只产生一个项目',pair[0].data.orderId===pair[1].data.orderId&&Number(sql('select count(*) from jiedan_order'))===before+1)
q=pair[0].data;const project=(await call(boss,'GET','/jiedan/order/'+q.orderId)).data
ok('转项目金额保留',Number(project.amount)===4800.25);ok('转项目员工保留',project.ownerId===a.id);ok('转项目需求保留',project.requirement==='企业官网含移动端');ok('转项目附件保留',project.attachments[0].url===attachment.url);ok('新项目等待员工接收',project.assignState==='pending');ok('报价说明进内部记录',JSON.stringify(project.timeline).includes('工期 10 天'))
ok('客户不能查看报价沟通',denied(await call(client,'GET','/jiedan/quote/'+q.id+'/conversation')))
ok('转项目保留沟通历史并关闭新回复', (await call(ta,'GET','/jiedan/quote/'+q.id+'/conversation')).data.items.some(m=>m.kind==='price') && denied(await call(boss,'POST','/jiedan/quote/'+q.id+'/conversation',{employeeId:a.id,clientKey:'after-convert-key',content:'test'})))
ok('已转换不允许再报价',denied(await call(ta,'PUT',`/jiedan/quote/${q.id}/price`,{version:q.version,amount:1})))
ok('已转换不允许再分配',denied(await call(boss,'PUT',`/jiedan/quote/${q.id}/assign`,{version:q.version,employeeId:b.id})))
ok('删除已转换报价',good(await call(boss,'DELETE','/jiedan/quote/'+q.id,{version:q.version})))
ok('删除报价不删除项目',good(await call(boss,'GET','/jiedan/order/'+q.orderId)))
ok('已删除报价不再可访问',denied(await call(ta,'GET','/jiedan/quote/'+q.id)))
ok('已删除报价不在列表',!(await call(boss,'GET','/jiedan/quote/list')).data.some(x=>x.id===q.id))
let r=(await make()).data;r=(await call(ta,'PUT',`/jiedan/quote/${r.id}/price`,{version:r.version,amount:2300})).data
r=(await call(boss,'PUT',`/jiedan/quote/${r.id}/assign`,{version:r.version,employeeId:b.id})).data
ok('重新指派清空报价',r.status==='pending'&&r.amount==null&&r.employeeId===b.id)
ok('旧员工立即失去访问权',denied(await call(ta,'GET','/jiedan/quote/'+r.id)))
ok('新员工获得访问权',good(await call(tb,'GET','/jiedan/quote/'+r.id)))
ok('未成交报价可以删除',good(await call(boss,'DELETE','/jiedan/quote/'+r.id,{version:r.version})))
ok('删除报价不删除附件', (await fetch(BASE+attachment.url)).status===200)
const fingerprint=sql('select count(*) from jiedan_quote');for(let i=0;i<2;i++)ok('升级脚本可重复执行',sqlFile(fs.readFileSync(DEPLOY_DIR+'/upgrade_quote_v1.sql','utf8')).status===0)
ok('重复升级不改已有报价',sql('select count(*) from jiedan_quote')===fingerprint)
finish()
