import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { call, login, sql, sqlFile, BASE, DB, DEPLOY_DIR } from './lib.mjs'

assert.match(DB, /^xc_e2e_\d+$/)
assert.equal(new URL(BASE).hostname, '127.0.0.1')
const migration = readFileSync(DEPLOY_DIR + '/upgrade_customer_issue_confirm_v1.sql', 'utf8')
for (let i = 0; i < 2; i++) assert.equal(sqlFile(migration).status, 0)
const [admin, a, b] = await Promise.all([login('admin', 'admin123'), login('cust_a', 'customer123'), login('cust_b', 'customer123')])
const ok = async (token, method, endpoint, body) => {
  const res = await call(token, method, endpoint, body)
  assert.equal(res.code, 200, endpoint + ' ' + res.msg)
  return res.data
}
const deny = async (token, method, endpoint, body) => assert.notEqual((await call(token, method, endpoint, body)).code, 200, endpoint)
const tag = randomUUID().slice(0, 8)
const username = 'review' + tag
await ok(admin, 'POST', '/jiedan/staff', { userName: username, nickName: '确认测试', password: 'review123' })
const staff = await login(username, 'review123')
const owner = Number(sql("select user_id from sys_user where user_name='" + username + "'"))
sql("insert into jiedan_order(title,order_no,customer_account,owner_id,status,create_time) values('确认分轮','rv-" + tag + "','cust_a'," + owner + ",'doing',now())")
const order = Number(sql("select id from jiedan_order where order_no='rv-" + tag + "'"))
const img = async (token, name) => {
  const form = new FormData()
  form.append('file', new Blob([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/icEAAAAASUVORK5CYII=', 'base64')], {type:'image/png'}), name)
  const result = await (await fetch(BASE + '/jiedan/media/upload', {method:'POST', headers:{Authorization:'Bearer ' + token}, body:form})).json()
  assert.equal(result.code, 200, result.msg)
  return { ...result.data, type:'image', annotated:true }
}
const [before, after] = await Promise.all([img(a, '原图.png'), img(staff, '改后.png')])
const createIssue = async content => (await ok(a, 'POST', '/jiedan/portal/bug', {orderId:order, content, attachments:[before]})).bugs.find(i => i.content === content).id
const id = await createIssue('更改入口')
const otherId = await createIssue('其他需求')
const item = vo => vo.bugs.find(i => i.id === id)
const summaries = async () => (await ok(a, 'GET', '/jiedan/portal/orders')).find(o => o.id === order).requirements
const request = '/jiedan/order/bug/' + id + '/review'
const decision = round => '/jiedan/portal/bug/' + id + '/review/' + round
const body = {previousReviewId:null, requestKey:randomUUID(), beforeUrl:before.url, afterImage:after, content:'入口已移动'}
const normal = await ok(staff, 'POST', '/jiedan/order/bug/' + id + '/update', {content:'普通补充'})
assert.equal(item(normal).progressStatus, 'open')
sql("update jiedan_order set assign_state='pending' where id=" + order)
await deny(staff, 'POST', request, body)
await ok(staff, 'POST', '/jiedan/order/' + order + '/accept', {})
await deny(a, 'POST', request, body)
await deny(admin, 'POST', request, {...body, beforeUrl:'/other.png'})
const requests = await Promise.all([ok(staff, 'POST', request, body), ok(staff, 'POST', request, body)])
const invitation = item(requests[0]).review
assert.equal(item(requests[1]).review.id, invitation.id)
assert.equal(invitation.beforeImage.url, before.url)
assert.equal(invitation.afterImage.url, after.url)
assert.equal(invitation.beforeImage.annotated, true)
assert.equal(invitation.afterImage.annotated, true)
assert.equal(item(requests[1]).updates.at(-1).id, invitation.id)
assert.equal(item(requests[1]).status, 'confirm')
await deny(staff, 'POST', request, {...body, content:'另一邀请'})
await deny(staff, 'PUT', '/jiedan/order/bug/' + id + '/status', {status:'resolved'})
await deny(a, 'PUT', '/jiedan/portal/bug/' + id + '/status', {status:'resolved'})
await deny(a, 'POST', '/jiedan/portal/bug/' + id + '/update', {content:'旧版激活', reopen:true})
const summary = (await summaries()).find(i => i.id === id)
assert.equal(summary.reviewId, invitation.id)
assert.equal(summary.latestTeamReply.id, invitation.id)
assert.equal(summary.status, 'confirm')
await deny(b, 'POST', decision(invitation.id), {decision:'accept'})
await deny(a, 'POST', decision(invitation.id), {decision:'adjust', attachments:[]})
await deny(a, 'POST', '/jiedan/portal/bug/' + otherId + '/review/' + invitation.id, {decision:'accept'})
assert.equal(item(await ok(a, 'POST', '/jiedan/portal/bug/' + id + '/update', {content:'普通讨论'})).progressStatus, 'confirm')
await ok(staff, 'POST', '/jiedan/order/bug/' + otherId + '/update', {content:'另一需求有回复'})
const newer = item(await ok(staff, 'POST', '/jiedan/order/bug/' + id + '/update', {content:'新补充'})).updates.at(-1)
const adjustment = {decision:'adjust', content:'箭头还需要往左', attachments:[before]}
const revisions = await Promise.all([ok(a,'POST',decision(invitation.id),adjustment),ok(a,'POST',decision(invitation.id),adjustment)])
assert.equal(item(revisions[0]).review.state,'adjustment')
assert.equal(item(revisions[0]).progressStatus,'action')
const assigned = (await ok(staff,'GET','/jiedan/order/list')).find(x=>x.id===order)
assert.equal(assigned.ownerId, owner)
assert.equal(Number(assigned.actionBugCount), 1)
assert.equal(assigned.actionBugId, id)
assert(!Object.hasOwn(revisions[0],'collaborators'))
assert.equal(item(revisions[1]).updates.filter(u => u.content.includes('箭头还需要往左')).length,1)
assert.equal(sql('select update_id from jiedan_customer_issue_seen where customer_id=201 and bug_id='+id),String(invitation.id))
const rows = await summaries()
assert.equal(rows.find(x=>x.id===id).latestTeamReply.id,newer.id)
assert.equal(rows.find(x=>x.id===id).latestTeamReply.unread,true)
assert.equal(rows.find(x=>x.id===otherId).latestTeamReply.unread,true)
await deny(a,'POST',decision(invitation.id),{decision:'accept'})
await deny(staff,'POST',request,{...body,requestKey:randomUUID()})
const next = item(await ok(staff,'POST',request,{requestKey:randomUUID(), previousReviewId:invitation.id,content:'向左移动完成'})).review
assert.equal(next.afterImage, null)
await deny(a,'POST',decision(invitation.id),adjustment)
const final = await ok(a,'POST',decision(next.id),{decision:'accept'})
assert.equal(item(final).review.state,'accepted')
assert.equal(item(final).progressStatus,'resolved')
assert.equal((await summaries()).find(x=>x.id===id).latestTeamReply.unread,false)
const emptyOriginal = (await ok(a,'POST','/jiedan/portal/bug',{orderId:order,content:'先提交文字后补原图'})).bugs.at(-1).id
await ok(staff,'POST','/jiedan/order/bug/' + emptyOriginal + '/update',{content:'团队示意图',attachments:[after]})
await ok(a,'POST','/jiedan/portal/bug/' + emptyOriginal + '/update',{content:'客户补原图',attachments:[before]})
const fallback = (await ok(staff,'POST','/jiedan/order/bug/' + emptyOriginal + '/review',{requestKey:randomUUID(),content:'请确认'})).bugs.find(x=>x.id===emptyOriginal)
assert.equal(fallback.review.beforeImage.url, before.url)
assert.equal(fallback.review.afterImage,null)
const last = item(await ok(staff,'POST',request,{...body,requestKey:randomUUID(),previousReviewId:next.id})).review
await ok(admin,'PUT','/jiedan/order',{id:order,customerAccount:'cust_b'})
await deny(a,'POST',decision(last.id),{decision:'accept'})
await deny(b,'POST',decision(last.id),{decision:'accept'})
const rebound = item(await ok(b,'GET','/jiedan/portal/orders/'+order))
assert.equal(rebound.review,null)
assert.equal(rebound.progressStatus,'resolved')
assert.equal(sql('select state from jiedan_issue_review where update_id='+last.id),'cancelled')
await ok(admin,'DELETE','/jiedan/order/bug/'+id)
assert.equal(sql('select count(*) from jiedan_issue_review where bug_id='+id),'0')
console.log('PASS confirmation rounds, paired snapshots, scoped invitations, concurrent retries, independent unread cursors, assignee rework, stale decisions, rebind and cascades')
