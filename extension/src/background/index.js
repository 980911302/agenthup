chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {})

chrome.tabs.onUpdated.addListener((tabId, info, tab) => {
  if (info.status !== 'complete' || !tab?.url) return
  if (!tab.url.includes('/oauth2/login/extension/callback')) return
  let parsed
  try {
    parsed = new URL(tab.url)
  } catch (_) {
    return
  }
  const ticket = parsed.searchParams.get('oauthTicket')
  const error = parsed.searchParams.get('oauthError')
  chrome.storage.local.set({
    oauthTicket: ticket || '',
    oauthError: error || '',
    oauthAt: Date.now()
  })
  chrome.tabs.remove(tabId).catch(() => {})
})
