import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {readFileSync} from 'node:fs'
import {call, login, sql, sqlFile, DB, BASE, DEPLOY_DIR} from './lib.mjs'

assert.match(DB,/^xc_e2e_\d+$/)
assert.equal(new URL(BASE).hostname,'127.0.0.1')
const migration = readFileSync(DEPLOY_DIR+'/upgrade_customer_issue_replies_v1.sql','utf8')
for (let i=0; i<2; i++) assert.equal(sqlFile(migration).status,0)
const [customer,other,admin] = await Promise.all([login('cust_a','customer123'),login('cust_b','customer123'),login('admin','admin123')])
assert(customer && other && admin)
const key = randomUUID()
sql("insert into jiedan_order(title,order_no,customer_account,owner_id,status,create_time) values('客户回复收件箱','reply-"+key.slice(0,8)+"','cust_a',1,'doing',now())")
const order = Number(sql("select id from jiedan_order where order_no='reply-"+key.slice(0,8)+"'"))
async function post(token, path, body) {
  const result = await call(token,'POST',path,body)
  assert.equal(result.code,200,result.msg)
  return result.data
}
const photo={url:'/jiedan/media/test-photo.png',name:'首图.png',type:'image'}
const doc={url:'/jiedan/media/test-doc.pdf',name:'需求.pdf',type:'file'}
const a=(await post(customer,'/jiedan/portal/bug',{orderId:order,content:'第一条',attachments:[doc,photo,photo]})).bugs.find(b=>b.content==='第一条').id
const b=(await post(customer,'/jiedan/portal/bug',{orderId:order,content:'第二条'})).bugs.find(b=>b.content==='第二条').id
async function rows() {
  return (await call(customer,'GET','/jiedan/portal/orders')).data.find(p=>p.id===order).requirements
}
const summary=async id=>(await rows()).find(b=>b.id===id)
const read=(token, id, update, orderId=order)=>call(token,'POST','/jiedan/portal/orders/'+orderId+'/issues/'+id+'/replies/'+update+'/read')
const detail=async ()=>(await call(customer,'GET','/jiedan/portal/orders/'+order)).data
const updates=(vo,id)=>vo.bugs.find(b=>b.id===id).updates
sql("insert into jiedan_bug_update(bug_id,content,created_by,create_time) values("+a+",'旧版记录','管理员',now())")
let item=await summary(a)
assert.equal(item.imageCount,2)
assert.equal(item.firstImage.url,photo.url)
assert(!Object.hasOwn(item,'rawAttachments'))
assert(!item.latestTeamReply)
assert(!updates(await detail(),a)[0].senderKind)
const staffReply=async (id,content,attachments=[])=>updates(await post(admin,'/jiedan/order/bug/'+id+'/update',{content,attachments}),id).at(-1)
const oldReply=await staffReply(a,'已调整字号')
const newer=await staffReply(a,'',[photo])
const replyB=await staffReply(b,'请确认')
assert.equal(newer.senderKind,'team')
assert.equal((await summary(a)).latestTeamReply.id,newer.id)
assert.equal((await summary(a)).latestTeamReply.hasAttachments,true)
assert.equal((await summary(a)).latestTeamReply.unread,true)
assert.notEqual((await read(other,a,newer.id)).code,200)
assert.notEqual((await read(customer,a,replyB.id)).code,200)
assert.notEqual((await read(customer,a,newer.id,order+1)).code,200)
assert.equal((await read(customer,a,oldReply.id)).code,200)
assert.equal((await summary(a)).latestTeamReply.unread,true)
await Promise.all([read(customer,a,newer.id),read(customer,a,oldReply.id)])
assert.equal((await summary(a)).latestTeamReply.unread,false)
assert.equal((await summary(b)).latestTeamReply.unread,true)
const close=await call(customer,'PUT','/jiedan/portal/bug/'+a+'/status',{status:'resolved'})
assert.equal(close.code,200,close.msg)
const body={content:'仍需调整',attachments:[photo],requestKey:key,reopen:true}
const endpoint='/jiedan/portal/bug/'+a+'/update'
const both=await Promise.all([post(customer,endpoint,body),post(customer,endpoint,body)])
for(const vo of both) {
  assert.equal(vo.bugs.find(i=>i.id===a).progressStatus,'action')
  const added=updates(vo,a).filter(u=>u.content==='仍需调整')
  assert.equal(added.length,1)
  assert.equal(added[0].senderKind,'customer')
  assert.equal(added[0].attachments.length,1)
  assert.notEqual((await read(customer,a,added[0].id)).code,200)
}
assert.notEqual((await call(customer,'POST',endpoint,{...body,content:'另一项修改'})).code,200)
assert.equal((await summary(a)).latestTeamReply.id,newer.id)
await post(customer,endpoint,{content:'旧客户端普通追加'})
sql("update jiedan_order set status='done' where id="+order)
const count=updates(await detail(),a).length
assert.notEqual((await call(customer,'POST',endpoint,{...body,requestKey:randomUUID()})).code,200)
assert.equal(updates(await detail(),a).length,count)
assert(!((await call(other,'GET','/jiedan/portal/orders')).data.some(p=>p.id===order)))
assert.equal((await call(admin,'DELETE','/jiedan/order/bug/'+a)).code,200)
assert.equal(sql('select count(*) from jiedan_customer_issue_seen where bug_id='+a),'0')
assert.equal(sql('select count(*) from jiedan_bug_reply_meta where bug_id='+a),'0')
console.log('PASS schema replay, legacy updates, image summary, independent seen cursors, role attribution, atomic rework, concurrent retries, project isolation and cleanup')
