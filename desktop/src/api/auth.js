/** 协议层副本：extension/src/api/auth.js。OAuth 入口 desktop/config ↔ extension/config。 */
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

// Desktop 使用独立的 OAuth 回调目标，授权完成后回到 /desktop/login。

export function getDesktopOauthLoginConfig() {
  return request({
    url: '/oauth2/login/desktop/config',
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
