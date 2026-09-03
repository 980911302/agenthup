/** 协议层副本：desktop/src/api/auth.js。OAuth 入口走 /oauth2/login/extension/config。 */
import request from './request'

export function login(username, password, code, uuid) {
  return request({
    url: '/login',
    method: 'post',
    headers: { isToken: false, repeatSubmit: false },
    data: { username, password, code, uuid }
  })
}

export function getInfo() {
  return request({ url: '/getInfo', method: 'get' })
}

export function logout() {
  return request({ url: '/logout', method: 'post' })
}

export function getCodeImg() {
  return request({
    url: '/captchaImage',
    method: 'get',
    headers: { isToken: false },
    silent: true,
    timeout: 20000
  })
}

export function getExtensionOauthLoginConfig() {
  return request({
    url: '/oauth2/login/extension/config',
    method: 'get',
    headers: { isToken: false },
    silent: true
  })
}

export function exchangeOauthTicket(ticket) {
  return request({
    url: '/oauth2/login/exchange',
    method: 'post',
    headers: { isToken: false, repeatSubmit: false },
    data: { ticket }
  })
}
