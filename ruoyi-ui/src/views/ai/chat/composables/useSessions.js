import { ref, computed, provide, getCurrentInstance } from 'vue'
import { listSession, getSessionTimeline, delSession as apiDelSession } from '@/api/ai/session'
import { getActiveChatRun, getLatestChatRun } from '@/api/ai/chat'
import { buildTurns, applyRunStates } from './useTurnBuilder'

const LAST_SESSION_KEY = 'ai.chat.lastSessionId'
/** 时间线每页消息条数:进入会话只渲染最新一页(50 条≈十几轮),滚动到顶再加载更早 */
const TIMELINE_PAGE_SIZE = 50

/**
 * 生成新会话 id。
 *
 * <p>这个 id 不只是标识符 —— 会话落库前后端无法比对属主(库里还查不到这一行),
 * 事件预订阅只能靠「猜不到 id」兜底。所以它必须是密码学强随机,
 * 用 Math.random 降级会让 id 可预测,直接击穿那层兜底。
 *
 * <p>crypto.randomUUID 只在安全上下文(HTTPS / localhost)下存在,
 * 但 crypto.getRandomValues 不受此限制 —— 所以降级路径用它手工拼 UUIDv4,
 * 强度与 randomUUID 完全一致(122 位真随机),而不是退回弱随机。
 * 两者都拿不到时宁可抛错,也不生成一个不安全的 id。
 */
function genId() {
  const c = typeof crypto !== 'undefined' ? crypto : null
  if (c && c.randomUUID) return c.randomUUID()
  if (c && c.getRandomValues) {
    const b = c.getRandomValues(new Uint8Array(16))
    b[6] = (b[6] & 0x0f) | 0x40 // version 4
    b[8] = (b[8] & 0x3f) | 0x80 // variant 10
    const hex = Array.from(b, x => x.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  throw new Error('当前环境缺少 Web Crypto，无法安全地创建会话')
}

function formatTokens(n) {
  const v = Number(n) || 0
  return v >= 1000 ? (v / 1000).toFixed(1).replace(/\.0$/, '') + 'K' : String(v)
}

/**
 * 会话状态与切换流程 composable。
 *
 * <p>覆盖：会话列表/类型筛选/上次会话恢复、当前会话身份(currentSessionId/conversationId)、
 * 顶栏标题派生、会话切换与时间线重建、删除/新建/远端轮次重建。
 * 它不持有 run，而是通过注入的 run/agent/scrollToBottom/meta 操作对话主流程 ——
 * 编排层把 useChatRun / useAgent / useChatMeta 的返回值与 ChatBody 的滚动能力接进来。
 */
export function useSessions(options = {}) {
  const { run, agent, scrollToBottom, meta, getTurnCount } = options
  const { proxy } = getCurrentInstance()

  const sessions = ref([])
  /** 会话类型筛选：'' 全部 / chat / job */
  const sessionTypeFilter = ref('')
  /** 顶栏会话搜索关键字：标题 / 智能体名模糊匹配 */
  const sessionSearch = ref('')
  const currentSessionId = ref(null)
  /** 新会话占位 id；落库后与 currentSessionId 一致。 */
  const conversationId = ref(genId())
  /**
   * 当前会话是否已在服务端落库。
   *
   * <p>currentSessionId 非空并不等于会话存在 —— 新会话首轮发送前，
   * markSessionCreated() 就先把本地生成的占位 id 顶上去了（为了让它出现在侧栏、
   * 让刻度条能刷新），此时后端还没有这一行。任何「按 sessionId 打后端」的副作用
   * 都必须等这个标志为真，否则就是拿一个不存在的 id 去查，稳定报 500。
   */
  const sessionPersisted = ref(false)
  /** 当前会话选中的知识库 kbId 列表(会话级多选;切换会话时由 s.kbIds 逗号串恢复) */
  const sessionKbIds = ref([])
  const sessionLoading = ref(false)
  let initialSessionRestored = false
  let sessionSwitchVersion = 0
  /** 是否还有更早消息(分页游标用) */
  const timelineHasMore = ref(false)
  const timelineLoadingMore = ref(false)
  /**
   * 时间线首屏加载中。与 timelineLoadingMore(加载更早)不是一回事。
   *
   * <p>切会话时先 setTurns([]) 再异步拉历史,中间这段 turns.length === 0,
   * ChatBody 会把「新会话欢迎页」渲染出来,数据到了再被顶掉 —— 看起来就是
   * 聊天记录闪了一下没了。亮着这个标志时空态不渲染,改出骨架屏。
   */
  const timelineLoading = ref(false)

  // 暴露当前 sessionId 给子孙组件(如 ToolStep 渲染生图附件时拼下载链接用)
  provide('sessionId', currentSessionId)

  /** 搜索过滤后的侧栏列表：关键字命中标题或智能体名 */
  const filteredSessions = computed(() => {
    const kw = sessionSearch.value.trim().toLowerCase()
    if (!kw) return sessions.value
    return sessions.value.filter(s =>
      (s.title || '').toLowerCase().includes(kw) ||
      (s.supervisorAgentName || '').toLowerCase().includes(kw)
    )
  })

  // 顶栏:当前会话的标题 + 本轮用量概览(取代原先的标题+chip+智能体名+一堆按钮)
  const headerTitle = computed(() => {
    const s = sessions.value.find(x => x.sessionId === currentSessionId.value)
    if (s && s.title) return s.title
    return getTurnCount() ? '当前对话' : 'AI 对话'
  })
  const headerSub = computed(() => {
    const s = sessions.value.find(x => x.sessionId === currentSessionId.value)
    if (!s) return agent.currentAgent.value ? agent.currentAgent.value.agentName : ''
    const bits = []
    if (agent.currentAgent.value) bits.push(agent.currentAgent.value.agentName)
    if (s.llmCallCount) bits.push(`${s.llmCallCount} 次调用`)
    if (s.totalTokens) bits.push(`${formatTokens(s.totalTokens)} tok`)
    return bits.join(' · ')
  })

  function buildSessionQuery() {
    const q = { pageNum: 1, pageSize: 50 }
    if (sessionTypeFilter.value) q.sessionType = sessionTypeFilter.value
    return q
  }

  function onSessionTypeFilter(type) {
    sessionTypeFilter.value = type || ''
    loadSessions()
  }

  /**
   * 拉取会话列表。
   *
   * <p>silent=true 时不亮 loading 遮罩。侧栏的 v-loading 是整块灰色蒙层，
   * 只有「用户自己发起、并且在等结果」的加载才配得上它 —— 首次进入、切换类型筛选。
   * 发送后补一行、本轮结束后更新 token 数这类后台对账每轮要跑两次，
   * 亮遮罩就成了聊两句左边闪两下。
   */
  function loadSessions(options = {}) {
    const silent = options.silent === true
    if (!silent) sessionLoading.value = true
    listSession(buildSessionQuery()).then((res) => {
      sessions.value = res.rows || []
      if (!initialSessionRestored) {
        initialSessionRestored = true
        const savedId = sessionStorage.getItem(LAST_SESSION_KEY)
        const saved = sessions.value.find(item => item.sessionId === savedId)
        if (saved) switchSession(saved)
        // 首轮 POST 与页面刷新可能并发：新页面第一次 list 时，旧请求的创建事务尚未提交。
        // 短时重查可恢复该运行，避免把仍在执行的会话误判成不存在。
        else if (savedId) retryRestoreSavedSession(savedId)
      }
    }).finally(() => { if (!silent) sessionLoading.value = false })
  }

  async function retryRestoreSavedSession(savedId) {
    const delays = [250, 500, 750, 1000, 1500]
    for (const delay of delays) {
      await new Promise(resolve => setTimeout(resolve, delay))
      if (sessionStorage.getItem(LAST_SESSION_KEY) !== savedId) return
      try {
        // 恢复指定会话时不套类型筛选，避免「任务会话」被对话筛选挡住
        const res = await listSession({ pageNum: 1, pageSize: 50 })
        sessions.value = res.rows || []
        const saved = sessions.value.find(item => item.sessionId === savedId)
        if (saved) {
          // 若当前筛选项会隐藏该会话，自动切到全部以便侧栏可见
          if (sessionTypeFilter.value && saved.sessionType && saved.sessionType !== sessionTypeFilter.value) {
            sessionTypeFilter.value = ''
          }
          await switchSession(saved)
          return
        }
      } catch (_) {
        // 网络恢复后继续下一次短时重查。
      }
    }
    if (sessionStorage.getItem(LAST_SESSION_KEY) === savedId) {
      sessionStorage.removeItem(LAST_SESSION_KEY)
    }
  }

  async function switchSession(s) {
    if (currentSessionId.value === s.sessionId) return
    const version = ++sessionSwitchVersion
    currentSessionId.value = s.sessionId
    conversationId.value = s.sessionId
    // 能从列表里切过来，说明它已经在库里了。
    sessionPersisted.value = true
    sessionStorage.setItem(LAST_SESSION_KEY, s.sessionId)
    // 先挂上会话监听再拉历史：晚于历史加载的话，这中间别处新开的一轮会漏掉。
    run.watchSession(s.sessionId)
    // 离开旧会话只是退订；旧 Agent 仍在后端继续执行。
    run.setTurns([])
    // 紧跟着清空亮起,中间不留 turns 为空又没有加载态的那一帧
    timelineLoading.value = true
    // 恢复该会话的智能体绑定：会话是用哪个智能体创建的，切回来就应该还是它。
    // 注意这里是程序赋值，不会触发 el-select 的 @change(onAgentChange)，
    // 所以不会误清空刚还原的对话。
    const restored = s.supervisorAgentId != null ? s.supervisorAgentId : null
    agent.setAgent(restored)
    // 恢复该会话的知识库选择：列表由后端 group_concat 带出逗号串，还原成本地多选数组
    sessionKbIds.value = s.kbIds
      ? s.kbIds.split(',').map(x => Number(x.trim())).filter(n => !Number.isNaN(n))
      : []
    meta.loadContextUsage(s.sessionId, restored)
    try {
      // 时间线是消息事实，run 状态负责修正活动/失败轮；二者并行读取。
      // 分页:只拉最新一页,避免全量渲染导致切会话卡顿。
      const [timelineRes, activeRes, latestRes] = await Promise.all([
        getSessionTimeline(s.sessionId, { limit: TIMELINE_PAGE_SIZE }),
        getActiveChatRun(s.sessionId).catch(() => ({ data: null })),
        getLatestChatRun(s.sessionId).catch(() => ({ data: null }))
      ])
      if (version !== sessionSwitchVersion) return
      const rows = timelineRes.data || []
      timelineHasMore.value = timelineRes.hasMore === true
      run.setTurns(buildTurns(rows, timelineRes.specialEvents, timelineRes.runs))
      if (activeRes.data) {
        await run.resume(activeRes.data)
      } else {
        await run.reconcileRun(latestRes.data)
        if (!run.turns.value.length) {
          run.setTurns([{ userMsg: null, steps: [{ type: 'content', text: '(暂无可展示的消息记录)', streaming: false }], completed: true }])
        }
      }
      scrollToBottom(true)
    } catch (e) {
      if (version === sessionSwitchVersion) proxy.$modal.msgError('加载会话失败，请重试')
    } finally {
      // 只有最后一次切换才熄灭:快速连切时先到的响应不能把后一次的加载态关掉,
      // 否则后一个会话还在路上,界面已经把空态露出来了。
      if (version === sessionSwitchVersion) timelineLoading.value = false
    }
  }

  /**
   * 新会话首轮发送/上传后由后端建行，这里先占上 currentSessionId，让它出现在列表里、刻度条能刷新。
   *
   * <p>注意这里<b>不</b>置 sessionPersisted —— 此刻后端那一行还没建出来。
   * 建行是后端的事实，由 markSessionPersisted() 在收到成功响应后确认。
   */
  function markSessionCreated() {
    if (!currentSessionId.value) {
      currentSessionId.value = conversationId.value
      sessionStorage.setItem(LAST_SESSION_KEY, conversationId.value)
    }
  }

  /** 后端已确认建行（首轮发送返回 / 附件上传成功）后调用，放行按 sessionId 的后续查询。 */
  function markSessionPersisted() {
    if (currentSessionId.value) sessionPersisted.value = true
  }

  function newConversation() {
    sessionSwitchVersion += 1
    conversationId.value = genId()
    currentSessionId.value = null
    sessionPersisted.value = false
    sessionKbIds.value = []
    sessionStorage.removeItem(LAST_SESSION_KEY)
    run.setTurns([])
    meta.resetContextUsage()
    run.watchSession(conversationId.value)
  }

  function deleteSession(s) {
    proxy.$modal.confirm('确认删除会话「' + (s.title || '新会话') + '」？').then(() => {
      return apiDelSession(s.sessionId)
    }).then(() => {
      sessions.value = sessions.value.filter(x => x.sessionId !== s.sessionId)
      if (currentSessionId.value === s.sessionId) newConversation()
      proxy.$modal.msgSuccess('已删除')
    }).catch(() => {})
  }

  /**
   * 加载更早的消息:游标取上一页,拼到现有 turns 最前(不中断活动 run)。
   * 后端保证每页第一条是 USER(完整轮次);新页最后一个若无 USER(半轮尾部),
   * 并入现有第一轮的 steps,避免出现孤立步骤。
   */
  async function loadOlderMessages() {
    if (timelineLoadingMore.value || !timelineHasMore.value || !currentSessionId.value) return
    const first = run.turns.value.find(t => t.userMsg && t.userMsg.messageId)
    if (!first) return
    timelineLoadingMore.value = true
    try {
      const res = await getSessionTimeline(currentSessionId.value, {
        limit: TIMELINE_PAGE_SIZE,
        beforeMessageId: first.userMsg.messageId
      })
      const rows = res.data || []
      timelineHasMore.value = res.hasMore === true
      if (!rows.length) {
        timelineHasMore.value = false
        return
      }
      const olderTurns = buildTurns(rows, res.specialEvents, res.runs)
      if (olderTurns.length) {
        const tail = olderTurns[olderTurns.length - 1]
        if (!tail.userMsg && run.turns.value.length && run.turns.value[0].userMsg) {
          // 半轮尾部:步骤并入现有第一轮(其 USER 就是这条轮次的起点)
          if (tail.steps && tail.steps.length) {
            run.turns.value[0].steps = [...tail.steps, ...run.turns.value[0].steps]
          }
          olderTurns.pop()
        }
      }
      // 直接 unshift 保持响应式;不走 setTurns,避免 detach 中断活动 run 订阅
      run.turns.value.unshift(...olderTurns)
      // 更早那页的末轮在本页视角里不再是最后一轮,按结构性判据收掉它可能残留的「执行中」
      applyRunStates(run.turns.value)
    } finally {
      timelineLoadingMore.value = false
    }
  }

  /**
   * 用消息事实表重建当前会话。别处结束一轮后本页据此落地最终结果 ——
   * 不去猜流式内容，只认库里的消息。
   * 分页下只重拉最新一页,已加载的更早轮次按 messageId 保留合并。
   */
  async function reloadTimeline(sessionId) {
    if (sessionId !== conversationId.value) return
    const version = sessionSwitchVersion
    try {
      const res = await getSessionTimeline(sessionId, { limit: TIMELINE_PAGE_SIZE })
      if (version !== sessionSwitchVersion || sessionId !== conversationId.value) return
      const rows = res.data || []
      timelineHasMore.value = res.hasMore === true
      const latestTurns = buildTurns(rows || [], res.specialEvents, res.runs)
      // 保留现有比最新页更早的轮次,再按 messageId 升序合并
      const latestIds = new Set(latestTurns.map(t => t.userMsg && t.userMsg.messageId).filter(Boolean))
      const older = turnsRef().filter(t => t.userMsg && !latestIds.has(t.userMsg.messageId))
      const merged = [...older, ...latestTurns].sort((a, b) =>
        (a.userMsg && a.userMsg.messageId || 0) - (b.userMsg && b.userMsg.messageId || 0))
      // 合并后位次变了:原本处在末位的轮次现在有了后继,不能再挂着「执行中」
      applyRunStates(merged, res.runs)
      run.setTurns(merged)
      scrollToBottom(true)
      loadSessions({ silent: true })
      meta.loadContextUsage(sessionId, agent.agentId.value)
    } catch (_) {
      // 拉取失败不动现有内容，下一次事件或切换会话时再重建。
    }
  }

  /** 当前 turns 数组(合并逻辑用,不触发 setTurns) */
  function turnsRef() {
    return run.turns.value
  }

  return {
    sessions,
    filteredSessions,
    sessionSearch,
    sessionTypeFilter,
    currentSessionId,
    conversationId,
    sessionPersisted,
    sessionKbIds,
    sessionLoading,
    headerTitle,
    headerSub,
    loadSessions,
    onSessionTypeFilter,
    switchSession,
    newConversation,
    markSessionCreated,
    markSessionPersisted,
    deleteSession,
    reloadTimeline,
    loadOlderMessages,
    timelineHasMore,
    timelineLoadingMore,
    timelineLoading
  }
}
