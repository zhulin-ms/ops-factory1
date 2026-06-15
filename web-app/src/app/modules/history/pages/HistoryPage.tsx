import { useState, useEffect, useMemo, useCallback } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useGoosed } from '../../../platform/providers/GoosedContext'
import { useInbox } from '../../../platform/providers/InboxContext'
import { useToast } from '../../../platform/providers/ToastContext'
import { useConfirmDialog } from '../../../platform/providers/ConfirmDialogContext'
import { useUser } from '../../../platform/providers/UserContext'
import PageHeader from '../../../platform/ui/primitives/PageHeader'
import Pagination from '../../../platform/ui/primitives/Pagination'
import ListFooter from '../../../platform/ui/list/ListFooter'
import ListSearchInput from '../../../platform/ui/list/ListSearchInput'
import ListToolbar from '../../../platform/ui/list/ListToolbar'
import ListWorkbench from '../../../platform/ui/list/ListWorkbench'
import FilterSelect from '../../../platform/ui/filters/FilterSelect'
import { buildChatSessionState } from '../../../platform/chat/chatRouteState'
import { runtime, gatewayHeaders, isScheduledSession } from '../../../../config/runtime'
import { trackedFetch } from '../../../platform/logging/requestClient'
import RenameSessionDialog from '../components/RenameSessionDialog'
import SessionList, { type SessionWithAgent } from '../components/SessionList'
import { useHistorySessions } from '../hooks/useHistorySessions'
import { downloadBlobResponse } from '../../../../utils/download'

type HistoryFilter = 'user' | 'scheduled' | 'agent_call' | 'all'

type TraceJobStatus = 'running' | 'succeeded' | 'failed'

interface TraceJobResponse {
    jobId: string
    status: TraceJobStatus
    fileName?: string
    message?: string
}

function parseHistoryFilter(raw: string | null): HistoryFilter {
    if (raw === 'scheduled' || raw === 'agent_call' || raw === 'all' || raw === 'user') return raw
    return 'user'
}

const ALL_AGENTS = '__all__'
const TRACE_POLL_INTERVAL_MS = 1500
const TRACE_POLL_TIMEOUT_MS = 10 * 60 * 1000

function wait(ms: number): Promise<void> {
    return new Promise(resolve => window.setTimeout(resolve, ms))
}

async function getResponseError(response: Response): Promise<string> {
    const text = await response.text().catch(() => '')
    if (!text) return `HTTP ${response.status}`

    try {
        const body = JSON.parse(text)
        return body.message || body.error || text
    } catch {
        return text
    }
}

export default function HistoryPage() {
    const { t } = useTranslation()
    const navigate = useNavigate()
    const { showToast } = useToast()
    const { requestConfirm } = useConfirmDialog()
    const { userId } = useUser()
    const [searchParams, setSearchParams] = useSearchParams()
    const { getClient, agents, isConnected, error: connectionError } = useGoosed()
    const { markSessionRead, markSessionUnread } = useInbox()
    const [deletingSessionKeys, setDeletingSessionKeys] = useState<Set<string>>(new Set())
    const [tracingSessionKeys, setTracingSessionKeys] = useState<Set<string>>(new Set())
    const [renamingSession, setRenamingSession] = useState<SessionWithAgent | null>(null)
    const [isRenaming, setIsRenaming] = useState(false)
    const [currentPage, setCurrentPage] = useState(1)
    const [pageSize, setPageSize] = useState(20)
    const historyFilter = parseHistoryFilter(searchParams.get('type'))
    const selectedAgent = useMemo(() => {
        const raw = searchParams.get('agent')
        if (!raw || raw === ALL_AGENTS) return ALL_AGENTS
        return agents.some((agent) => agent.id === raw) ? raw : ALL_AGENTS
    }, [agents, searchParams])
    const [searchTerm, setSearchTerm] = useState('')
    const [debouncedSearch, setDebouncedSearch] = useState('')

    // Debounce search input
    useEffect(() => {
        const timer = setTimeout(() => setDebouncedSearch(searchTerm), 300)
        return () => clearTimeout(timer)
    }, [searchTerm])

    const canTraceSessions = true

    const getSessionKey = useCallback((session: SessionWithAgent) => `${session.agentId || 'unknown'}:${session.id}`, [])

    // Server-side paginated sessions
    const historySessions = useHistorySessions({
        pageIndex: currentPage,
        pageSize,
        search: debouncedSearch || undefined,
        agentId: selectedAgent === ALL_AGENTS ? undefined : selectedAgent,
        type: historyFilter === 'all' ? undefined : historyFilter,
    })

    const setHistoryFilter = useCallback((filter: HistoryFilter) => {
        const nextParams = new URLSearchParams(searchParams)
        if (filter === 'user') {
            nextParams.delete('type')
        } else {
            nextParams.set('type', filter)
        }
        setSearchParams(nextParams, { replace: true })
    }, [searchParams, setSearchParams])
    const setSelectedAgent = useCallback((agentId: string) => {
        const nextParams = new URLSearchParams(searchParams)
        if (!agentId || agentId === ALL_AGENTS) {
            nextParams.delete('agent')
        } else {
            nextParams.set('agent', agentId)
        }
        setSearchParams(nextParams, { replace: true })
    }, [searchParams, setSearchParams])

    // Reset page on filter/search/agent change
    useEffect(() => {
        setCurrentPage(1)
    }, [historyFilter, debouncedSearch, selectedAgent])

    const handleResumeSession = (session: SessionWithAgent) => {
        const resolvedAgentId = session.agentId || agents[0]?.id || ''
        if (resolvedAgentId && isScheduledSession(session)) {
            markSessionRead(resolvedAgentId, session.id)
        }
        navigate('/chat', {
            state: buildChatSessionState(session.id, resolvedAgentId),
        })
    }

    const handleMarkUnread = (session: SessionWithAgent) => {
        if (!isScheduledSession(session)) return
        const agentId = session.agentId || agents[0]?.id || ''
        if (!agentId) return
        markSessionUnread(agentId, session.id)
    }

    const handleRenameSession = useCallback((session: SessionWithAgent) => {
        setRenamingSession(session)
    }, [])

    const handleRenameSave = useCallback(async (nextTitle: string) => {
        if (!renamingSession) return

        const resolvedAgentId = renamingSession.agentId || agents[0]?.id || ''
        if (!resolvedAgentId) {
            showToast('error', t('history.renameSessionFailed'))
            return
        }

        setIsRenaming(true)
        try {
            await getClient(resolvedAgentId).updateSessionName(renamingSession.id, nextTitle)
            setRenamingSession(null)
            historySessions.refresh()
            showToast('success', t('history.renameSessionSuccess'))
        } catch (err) {
            console.error('Failed to rename session:', err)
            showToast('error', t('history.renameSessionFailed'))
        } finally {
            setIsRenaming(false)
        }
    }, [agents, getClient, renamingSession, showToast, t, historySessions.refresh])

    const pollTraceJob = useCallback(async (jobId: string): Promise<TraceJobResponse> => {
        const startedAt = Date.now()
        while (Date.now() - startedAt < TRACE_POLL_TIMEOUT_MS) {
            const response = await trackedFetch(`${runtime.GATEWAY_URL}/session-traces/${encodeURIComponent(jobId)}`, {
                category: 'request',
                name: 'request.send',
                headers: gatewayHeaders(userId),
            })
            if (!response.ok) {
                throw new Error(await getResponseError(response))
            }

            const job = await response.json() as TraceJobResponse
            if (job.status !== 'running') {
                return job
            }
            await wait(TRACE_POLL_INTERVAL_MS)
        }
        throw new Error(t('history.traceSessionTimeout'))
    }, [t, userId])

    const handleTraceSession = useCallback(async (session: SessionWithAgent) => {
        const resolvedAgentId = session.agentId || agents[0]?.id || ''
        if (!resolvedAgentId) {
            showToast('error', t('history.traceSessionFailed'))
            return
        }

        const sessionKey = getSessionKey({ ...session, agentId: resolvedAgentId })
        if (tracingSessionKeys.has(sessionKey)) return

        setTracingSessionKeys((prev) => new Set(prev).add(sessionKey))
        showToast('info', t('history.traceSessionStarted'))

        try {
            const startResponse = await trackedFetch(
                `${runtime.GATEWAY_URL}/agents/${encodeURIComponent(resolvedAgentId)}/sessions/${encodeURIComponent(session.id)}/trace`,
                {
                    method: 'POST',
                    category: 'request',
                    name: 'request.send',
                    headers: gatewayHeaders(userId),
                },
            )
            if (!startResponse.ok) {
                throw new Error(await getResponseError(startResponse))
            }

            const startedJob = await startResponse.json() as TraceJobResponse
            const completedJob = startedJob.status === 'running'
                ? await pollTraceJob(startedJob.jobId)
                : startedJob
            if (completedJob.status !== 'succeeded') {
                throw new Error(completedJob.message || t('history.traceSessionFailed'))
            }

            const downloadResponse = await trackedFetch(
                `${runtime.GATEWAY_URL}/session-traces/${encodeURIComponent(completedJob.jobId)}/download`,
                {
                    category: 'request',
                    name: 'request.send',
                    headers: gatewayHeaders(userId),
                },
            )
            if (!downloadResponse.ok) {
                throw new Error(await getResponseError(downloadResponse))
            }

            await downloadBlobResponse(
                downloadResponse,
                completedJob.fileName || `session-trace-${session.id}.tar.gz`,
            )
            showToast('success', t('history.traceSessionDownloaded'))
        } catch (err) {
            console.error('Failed to collect session trace:', err)
            showToast('error', err instanceof Error ? t('history.traceSessionFailedWithReason', { error: err.message }) : t('history.traceSessionFailed'))
        } finally {
            setTracingSessionKeys((prev) => {
                const next = new Set(prev)
                next.delete(sessionKey)
                return next
            })
        }
    }, [agents, getSessionKey, pollTraceJob, showToast, t, tracingSessionKeys, userId])

    const handleDeleteSession = async (session: SessionWithAgent) => {
        if (!(await requestConfirm({
            title: t('common.confirmTitle'),
            message: t('history.confirmDeleteSession'),
            variant: 'danger',
            confirmLabel: t('common.delete'),
        }))) return
        const resolvedAgentId = session.agentId || agents[0]?.id
        const sessionKey = getSessionKey({ ...session, agentId: resolvedAgentId })
        if (deletingSessionKeys.has(sessionKey)) return

        try {
            setDeletingSessionKeys((prev) => new Set(prev).add(sessionKey))
            if (resolvedAgentId) {
                await getClient(resolvedAgentId).deleteSession(session.id)
            } else {
                for (const agent of agents) {
                    await getClient(agent.id).deleteSession(session.id)
                    break
                }
            }
            showToast('success', t('history.sessionDeleted'))
            historySessions.refresh()
        } catch (err) {
            console.error('Failed to delete session:', err)
            const message = err instanceof Error ? err.message : 'Unknown error'
            if (message.includes('Resource not found')) {
                showToast('success', t('history.sessionDeleted'))
                historySessions.refresh()
                return
            }
            showToast('error', t('errors.deleteFailed'))
        } finally {
            setDeletingSessionKeys((prev) => {
                const next = new Set(prev)
                next.delete(sessionKey)
                return next
            })
        }
    }

    return (
        <div className="page-container sidebar-top-page page-shell-wide history-page">
            <PageHeader title={t('history.title')} subtitle={t('history.subtitle')} />

            {(historySessions.error || (!isConnected && connectionError)) && (
                <div className="conn-banner conn-banner-error">
                    {historySessions.error || t('common.connectionError', { error: connectionError })}
                </div>
            )}

            {tracingSessionKeys.size > 0 && (
                <div className="conn-banner conn-banner-warning">
                    {t('history.traceSessionActiveNotice')}
                </div>
            )}

            <ListWorkbench
                controls={(
                    <ListToolbar
                        primary={(
                            <div className="history-toolbar-controls">
                                <ListSearchInput
                                    value={searchTerm}
                                    placeholder={t('history.searchPlaceholder')}
                                    onChange={setSearchTerm}
                                />

                                <FilterSelect
                                    value={selectedAgent}
                                    options={[
                                        { value: ALL_AGENTS, label: t('history.allAgents') },
                                        ...agents.map((agent) => ({
                                            value: agent.id,
                                            label: agent.name,
                                        })),
                                    ]}
                                    onChange={setSelectedAgent}
                                    disabled={agents.length === 0}
                                />

                                <div className="seg-filter" role="tablist" aria-label="Session type filter">
                                    <button type="button" className={`seg-filter-btn ${historyFilter === 'user' ? 'active' : ''}`} onClick={() => setHistoryFilter('user')}>
                                        {t('history.filterUser')}
                                    </button>
                                    <button type="button" className={`seg-filter-btn ${historyFilter === 'scheduled' ? 'active' : ''}`} onClick={() => setHistoryFilter('scheduled')}>
                                        {t('history.filterScheduled')}
                                    </button>
                                    <button type="button" className={`seg-filter-btn ${historyFilter === 'agent_call' ? 'active' : ''}`} onClick={() => setHistoryFilter('agent_call')}>
                                        {t('history.filterAgentCall')}
                                    </button>
                                    <button type="button" className={`seg-filter-btn ${historyFilter === 'all' ? 'active' : ''}`} onClick={() => setHistoryFilter('all')}>
                                        {t('history.filterAll')}
                                    </button>
                                </div>
                            </div>
                        )}
                        secondary={undefined}
                    />
                )}
                footer={historySessions.total > 0 ? (
                    <ListFooter>
                        <Pagination
                            currentPage={currentPage}
                            totalPages={historySessions.totalPages}
                            pageSize={pageSize}
                            totalItems={historySessions.total}
                            onPageChange={setCurrentPage}
                            onPageSizeChange={(newSize) => {
                                setPageSize(newSize)
                                setCurrentPage(1)
                            }}
                            disabled={historySessions.isLoading}
                        />
                    </ListFooter>
                ) : undefined}
            >
                {debouncedSearch && historySessions.sessions.length === 0 && !historySessions.isLoading ? (
                    <div className="empty-state">
                        <svg className="empty-state-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                            <circle cx="11" cy="11" r="8" />
                            <line x1="21" y1="21" x2="16.65" y2="16.65" />
                        </svg>
                        <h3 className="empty-state-title">{t('common.noResults')}</h3>
                        <p className="empty-state-description">
                            {t('history.noMatchSessions', { term: debouncedSearch })}
                        </p>
                    </div>
                ) : (
                    <SessionList
                        sessions={historySessions.sessions}
                        isLoading={historySessions.isLoading}
                        onResume={handleResumeSession}
                        onRename={handleRenameSession}
                        onDelete={handleDeleteSession}
                        deletingSessionKeys={deletingSessionKeys}
                        tracingSessionKeys={tracingSessionKeys}
                        getSessionKey={getSessionKey}
                        agentNameById={Object.fromEntries(agents.map((agent) => [agent.id, agent.name]))}
                        onTrace={canTraceSessions ? handleTraceSession : undefined}
                        onMarkUnread={historyFilter !== 'user' ? handleMarkUnread : undefined}
                    />
                )}
            </ListWorkbench>

            {renamingSession && (
                <RenameSessionDialog
                    initialTitle={renamingSession.name || ''}
                    isSaving={isRenaming}
                    onClose={() => {
                        if (isRenaming) return
                        setRenamingSession(null)
                    }}
                    onSave={handleRenameSave}
                />
            )}
        </div>
    )
}
