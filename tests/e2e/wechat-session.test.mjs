import {BASE,login,call,ok,finish,denied} from './lib.mjs'
const token=await login('admin','admin123')
async function device(action,token,cookie,origin){
 const response=await fetch(BASE+'/jiedan/session/'+action,{method:'POST',headers:{
  'X-XC-Device':'1',...(token?{Authorization:'Bearer '+token}:{}),...(cookie?{cookie}:{}),...(origin?{Origin:origin}:{})
 }})
 return {body:await response.json(),cookie:response.headers.get('set-cookie'),cache:response.headers.get('cache-control')}
}
const saved=await device('remember',token)
ok('微信保持登录使用 30 天持久安全 cookie',saved.body.code===200 && /HttpOnly/i.test(saved.cookie) && /Secure/i.test(saved.cookie) && /SameSite=Strict/i.test(saved.cookie) && /Path=\/jiedan\/session/.test(saved.cookie) && /Max-Age=25919[0-9]{2}|Max-Age=2592000/.test(saved.cookie))
const cookie=saved.cookie.split(';')[0]
const resume=await device('restore',null,cookie)
ok('关闭页面后能恢复原会话，并继承原账号权限',resume.body.data?.token===token && resume.cache==='no-store' && (await call(resume.body.data.token,'GET','/jiedan/staff/me')).code===200)
ok('其他来源不能读取会话',(await device('restore',null,cookie,'https://other.invalid')).body.code===403)
ok('新设备不会继承别人的登录',!(await device('restore')).body.data?.token)
const out=await device('forget',token,cookie)
ok('主动退出立即撤销 cookie 及同一会话令牌',out.body.code===200 && /Max-Age=0/.test(out.cookie) && !(await device('restore',null,cookie)).body.data?.token && denied(await call(token,'GET','/jiedan/staff/me')))
finish()
