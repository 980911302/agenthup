/**
 * MAIN world、document_start。包住 fetch/XHR，把摘要+正文通过 postMessage 交给 ISOLATED。
 * 不能用 chrome.*，也不能 import。整文件必须幂等：已打开的页会再次 executeScript。
 */
(function agenthubNetPatch() {
  if (window.__agenthubNetPatched) return
  window.__agenthubNetPatched = true

  const SOURCE = 'agenthub-net'
  const BODY_CAP = 200000
  const REQ_CAP = 8000

  function emit(record) {
    try {
      window.postMessage({ source: SOURCE, type: 'captured', record: record }, '*')
    } catch (_) { /* 页面可能冻结 postMessage */ }
  }

  function clip(s, n) {
    if (s == null) return ''
    const t = typeof s === 'string' ? s : String(s)
    return t.length > n ? t.slice(0, n) : t
  }

  const origFetch = window.fetch
  if (typeof origFetch === 'function') {
    window.fetch = function agenthubFetch(input, init) {
      const t0 = performance.now()
      const method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase()
      const url = typeof input === 'string' ? input : (input && input.url) ? String(input.url) : String(input)
      const reqBody = init && typeof init.body === 'string' ? clip(init.body, REQ_CAP) : ''
      return origFetch.apply(this, arguments).then((res) => {
        const ct = res.headers.get('content-type') || ''
        const len = Number(res.headers.get('content-length') || 0)
        if (ct.includes('event-stream') || len > BODY_CAP * 4) {
          emit({
            method: method, url: url, status: res.status,
            duration: Math.round(performance.now() - t0),
            size: len, requestBody: reqBody, responseBody: '',
            note: ct.includes('event-stream') ? '流式响应，未抓正文' : '响应过大，未抓正文'
          })
          return res
        }
        const copy = res.clone()
        copy.text().then((text) => {
          emit({
            method: method,
            url: url,
            status: res.status,
            duration: Math.round(performance.now() - t0),
            size: (text || '').length,
            requestBody: reqBody,
            responseBody: clip(text, BODY_CAP)
          })
        }).catch(() => {
          emit({
            method: method, url: url, status: res.status,
            duration: Math.round(performance.now() - t0), size: 0,
            requestBody: reqBody, responseBody: ''
          })
        })
        return res
      }, (err) => {
        emit({
          method: method, url: url, status: 0,
          duration: Math.round(performance.now() - t0), size: 0,
          requestBody: reqBody, responseBody: '', error: err && err.message
        })
        throw err
      })
    }
  }

  const XHR = window.XMLHttpRequest
  if (XHR && XHR.prototype) {
    const origOpen = XHR.prototype.open
    const origSend = XHR.prototype.send
    XHR.prototype.open = function (method, url) {
      this.__agenthub = { method: String(method || 'GET').toUpperCase(), url: String(url || '') }
      return origOpen.apply(this, arguments)
    }
    XHR.prototype.send = function (body) {
      const meta = this.__agenthub || { method: 'GET', url: '' }
      meta.t0 = performance.now()
      meta.requestBody = typeof body === 'string' ? clip(body, REQ_CAP) : ''
      this.addEventListener('loadend', function () {
        let responseBody = ''
        try { responseBody = clip(this.responseText, BODY_CAP) } catch (_) { responseBody = '' }
        emit({
          method: meta.method,
          url: meta.url,
          status: this.status || 0,
          duration: Math.round(performance.now() - (meta.t0 || performance.now())),
          size: responseBody.length,
          requestBody: meta.requestBody || '',
          responseBody: responseBody
        })
      })
      return origSend.apply(this, arguments)
    }
  }
})()
