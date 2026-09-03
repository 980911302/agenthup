/**
 * 操作类工具的确认策略。
 *
 * 默认**不弹确认**。页面操作（点击、填表、导航）都作用在用户眼前的页面上，
 * 做了什么当场就看得见，弹窗只是把已经看到的事再问一遍。
 *
 * fetchWithSession 曾是唯一例外（带登录态在后台发请求，用户看不到），现按产品决定
 * 也不弹。它的防护改由工具自身两道硬约束承担，见 browserTools.js：
 *   1) 只允许 GET —— 写操作直接拒绝；
 *   2) 只允许当前页同源，或 chrome.storage.local.fetchAllowHosts 白名单内的主机。
 * 即使页面正文诱导 AI 发请求，也越不出这个页面自己的域。
 *
 * 侧栏已无设置页（见 8fa5a70），所以没有开关 UI。需要逐步确认时在扩展的
 * 控制台执行：chrome.storage.local.set({ confirmMode: 'all' })，改回用 'none'。
 */

const KEY = 'confirmMode'

let cached = null

/** 该工具本次是否需要弹确认。默认全不弹，仅 confirmMode='all' 时全弹。 */
export async function shouldConfirm(_toolName) {
  if (cached === null) {
    try {
      const bag = await chrome.storage.local.get(KEY)
      cached = bag?.[KEY] === 'all' ? 'all' : 'none'
    } catch (_) {
      cached = 'none'
    }
  }
  return cached === 'all'
}
