import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import PageHeader from '../../../platform/ui/primitives/PageHeader'
import ListSearchInput from '../../../platform/ui/list/ListSearchInput'
import ListResultsMeta from '../../../platform/ui/list/ListResultsMeta'
import SearchableSelect from '../../../platform/ui/forms/SearchableSelect'
import { useHostGroups } from '../hooks/useHostGroups'
import { useClusters } from '../hooks/useClusters'
import { useHostResource } from '../hooks/useHostResource'
import { useHostRelations } from '../hooks/useHostRelations'
import { useClusterRelations } from '../hooks/useClusterRelations'
import { useToast } from '../../../platform/providers/ToastContext'
import { useConfirmDialog } from '../../../platform/providers/ConfirmDialogContext'
import { useBusinessServices } from '../hooks/useBusinessServices'
import { useClusterTypes } from '../hooks/useClusterTypes'
import { useBusinessTypes } from '../hooks/useBusinessTypes'
import { useSolutionTypes } from '../hooks/useSolutionTypes'
import { useCommandWhitelist } from '../hooks/useCommandWhitelist'
import { useSops } from '../hooks/useSops'
import { useResourceExport } from '../hooks/useResourceExport'
import { useResourceImport } from '../hooks/useResourceImport'
import ResourceTree, { type TreeNode, type TreeNodeType } from '../components/ResourceTree'
import ResourceFormModal from '../components/ResourceFormModal'
import HostCard from '../components/HostCard'
import RelationGraph from '../components/RelationGraph'
import ClusterInsightPanel from '../components/ClusterInsightPanel'
import ClusterTypeTab from '../components/ClusterTypeTab'
import BusinessTypeTab from '../components/BusinessTypeTab'
import SolutionTypeTab from '../components/SolutionTypeTab'
import { SopsTab } from '../components/SopsTab'
import { WhitelistTab } from '../components/WhitelistTab'
import ImportDialog from '../components/ImportDialog'
import type { HostGroup, Cluster, Host, HostCreateRequest, BusinessService } from '../../../../types/host'
import '../styles/host-resource.css'

type SelectedNode = {
    id: string
    type: TreeNodeType
}

type EditingItem =
    | { type: 'group'; data: HostGroup }
    | { type: 'cluster'; data: Cluster }
    | { type: 'business-service'; data: BusinessService }
    | { type: 'host'; data: Host }
    | null

type TabKey = 'overview' | 'topology' | 'cluster-types' | 'solution-types' | 'business-types' | 'sop-management' | 'whitelist'

const PAGE_SIZE = 6
const SIDEBAR_DEFAULT = 260
const SIDEBAR_MIN = 200
const SIDEBAR_MAX = 600
const SIDEBAR_STORAGE_KEY = 'host-resource-sidebar-width'

export default function HostResourcePage() {
    const { t } = useTranslation()
    const { showToast } = useToast()
    const { requestConfirm } = useConfirmDialog()
    const [activeTab, setActiveTab] = useState<TabKey>('overview')
    const [selected, setSelected] = useState<SelectedNode | null>(null)
    const [focusedHostId, setFocusedHostId] = useState<string | null>(null)
    const [selectedTopologyClusterId, setSelectedTopologyClusterId] = useState<string | null>(null)
    const [topologyGroupId, setTopologyGroupId] = useState('')
    const [selectedClusterIds, setSelectedClusterIds] = useState<Set<string>>(new Set())
    const [showModal, setShowModal] = useState(false)
    const [editingItem, setEditingItem] = useState<EditingItem>(null)
    const [currentPage, setCurrentPage] = useState(1)
    const [hostSearch, setHostSearch] = useState('')
    const [treeSearch, setTreeSearch] = useState('')
    const [showImportDialog, setShowImportDialog] = useState(false)
    const [testingId, setTestingId] = useState<string | null>(null)
    const [testResults, setTestResults] = useState<Record<string, { ok: boolean; msg: string }>>({})

    // Resizable sidebar
    const [sidebarWidth, setSidebarWidth] = useState(() => {
        const stored = localStorage.getItem(SIDEBAR_STORAGE_KEY)
        if (stored) {
            const v = parseInt(stored, 10)
            if (v >= SIDEBAR_MIN && v <= SIDEBAR_MAX) return v
        }
        return SIDEBAR_DEFAULT
    })
    const dragging = useRef(false)

    // Data hooks
    const { groups, fetchGroups, createGroup, updateGroup, deleteGroup } = useHostGroups()
    const { clusters, fetchAllClusters, createCluster, updateCluster, deleteCluster } = useClusters()
    const { hosts, allHosts, fetchHosts, fetchAllHosts, createHost, updateHost, deleteHost, testConnection } = useHostResource()
    const { relations: hostRelations, fetchGraph, fetchRelations: fetchHostRelations } = useHostRelations()
    const { relations: clusterRelations, clusterGraphData, fetchClusterGraph, fetchRelations: fetchClusterRelations, createRelation: createClusterRelation, updateRelation: updateClusterRelation, deleteRelation: deleteClusterRelation } = useClusterRelations()
    const { businessServices, fetchBusinessServices, createBusinessService, updateBusinessService, deleteBusinessService } = useBusinessServices()
    const clusterTypesHook = useClusterTypes()
    const businessTypesHook = useBusinessTypes()
    const solutionTypesHook = useSolutionTypes()
    const {
        commands: whitelistCommands,
        isLoading: whitelistLoading,
        error: whitelistError,
        addCommand: addWhitelistCommand,
        fetchWhitelist: fetchWhitelistCommands,
        updateCommand: updateWhitelistCommand,
        deleteCommand: deleteWhitelistCommand,
    } = useCommandWhitelist()
    const sopsHook = useSops()

    // Export / Import hooks
    const { exporting, exportAllAsZip } = useResourceExport()
    const { importing, progress, importXlsx } = useResourceImport({
        fetchGroups, fetchAllClusters, fetchAllHosts, fetchClusterRelations, fetchBusinessServices, fetchGraph, fetchWhitelist: fetchWhitelistCommands, fetchSops: sopsHook.fetchSops,
        groups, clusters, allHosts, businessServices, clusterRelations,
        clusterTypes: clusterTypesHook.clusterTypes,
        businessTypes: businessTypesHook.businessTypes,
        solutionTypes: solutionTypesHook.solutionTypes,
        createGroup, updateGroup, createCluster, createHost,
        createBusinessService, createClusterRelation,
        createClusterType: clusterTypesHook.createClusterType,
        createBusinessType: businessTypesHook.createBusinessType,
        createSolutionType: solutionTypesHook.createSolutionType,
        createSop: sopsHook.createSop,
        addWhitelistCommand,
    })

    // Load all data on mount
    useEffect(() => { fetchGroups() }, [fetchGroups])
    useEffect(() => { fetchAllClusters() }, [fetchAllClusters])
    useEffect(() => { fetchAllHosts() }, [fetchAllHosts])
    useEffect(() => { fetchBusinessServices() }, [fetchBusinessServices])
    useEffect(() => { fetchClusterGraph() }, [fetchClusterGraph])

    // Re-fetch topology graph when switching to the topology tab or changing group filter
    useEffect(() => {
        if (activeTab === 'topology') fetchClusterGraph(topologyGroupId || undefined)
    }, [activeTab, topologyGroupId, fetchClusterGraph])

    // Refresh host list respecting the current tree selection filter
    const refreshHostList = useCallback(() => {
        if (selected?.type === 'cluster') {
            fetchHosts(selected.id, undefined)
        } else if (selected?.type === 'business-service') {
            fetchHosts(undefined, undefined, selected.id)
        } else if (selected?.type === 'group' || selected?.type === 'subgroup') {
            fetchHosts(undefined, selected.id)
        } else {
            fetchHosts()
        }
    }, [selected, fetchHosts])

    // Fetch hosts based on tree selection
    useEffect(() => {
        refreshHostList()
    }, [refreshHostList])

    const topologyNodeMap = useMemo(() => {
        const map = new Map(clusterGraphData.nodes.map(node => [node.id, node]))
        return map
    }, [clusterGraphData])

    const selectedTopologyCluster = useMemo(() => {
        if (!selectedTopologyClusterId) return null
        const clusterFromList = clusters.find(cluster => cluster.id === selectedTopologyClusterId)
        if (clusterFromList) return clusterFromList

        const graphNode = topologyNodeMap.get(selectedTopologyClusterId)
        if (graphNode?.nodeType !== 'cluster') return null

        return {
            id: graphNode.id,
            name: graphNode.name,
            type: graphNode.type ?? graphNode.clusterType ?? '',
            purpose: '',
            description: '',
            groupId: graphNode.groupId ?? null,
            createdAt: '',
            updatedAt: '',
        } satisfies Cluster
    }, [selectedTopologyClusterId, clusters, topologyNodeMap])

    const selectedTopologyClusterHosts = useMemo(() => {
        if (!selectedTopologyClusterId) return []
        return allHosts.filter(host => host.clusterId === selectedTopologyClusterId)
    }, [allHosts, selectedTopologyClusterId])

    const topologyGraphData = useMemo(() => {
        const visibleNodeIds = new Set(
            clusterGraphData.nodes
                .filter(node => node.nodeType === 'cluster' || node.nodeType === 'business-service')
                .map(node => node.id),
        )

        return {
            nodes: clusterGraphData.nodes.filter(node => visibleNodeIds.has(node.id)),
            edges: clusterGraphData.edges.filter(edge =>
                edge.type !== 'constitute'
                && visibleNodeIds.has(edge.source)
                && visibleNodeIds.has(edge.target),
            ),
        }
    }, [clusterGraphData])

    // Build tree data — recursive, supports arbitrary nesting depth
    const treeData = useMemo((): TreeNode[] => {
        const clusterHostMap = new Map<string, number>()
        for (const h of allHosts) {
            if (h.clusterId) {
                clusterHostMap.set(h.clusterId, (clusterHostMap.get(h.clusterId) || 0) + 1)
            }
        }

        // Index children by parentId
        const childrenMap = new Map<string, HostGroup[]>()
        for (const g of groups) {
            if (g.parentId) {
                const list = childrenMap.get(g.parentId) || []
                list.push(g)
                childrenMap.set(g.parentId, list)
            }
        }

        const buildGroupNode = (g: HostGroup): TreeNode => {
            const childGroups = childrenMap.get(g.id) || []
            const childNodes: TreeNode[] = []

            // Recursively build child group nodes first
            for (const cg of childGroups) {
                childNodes.push(buildGroupNode(cg))
            }

            // Business services under this group
            const groupBs = businessServices.filter(bs => bs.groupId === g.id)
            for (const bs of groupBs) {
                const hostNames = bs.hostIds
                    .map(hid => allHosts.find(h => h.id === hid)?.name)
                    .filter(Boolean)
                    .join(', ')
                const businessType = bs.businessTypeId ? businessTypesHook.businessTypes.find(bt => bt.id === bs.businessTypeId) : null
                childNodes.push({
                    id: bs.id,
                    type: 'business-service' as TreeNodeType,
                    name: bs.name,
                    subtitle: hostNames || (businessType?.name || bs.code),
                    raw: bs,
                })
            }

            // Clusters under this group
            const groupClusters = clusters.filter(c => c.groupId === g.id)
            for (const c of groupClusters) {
                // Resolve cluster type name from clusterTypes by matching type field with name or code
                const clusterType = c.type ? clusterTypesHook.clusterTypes.find(ct => ct.name === c.type || ct.code === c.type) : null
                const displayType = clusterType?.name || c.type
                childNodes.push({
                    id: c.id,
                    type: 'cluster' as TreeNodeType,
                    name: c.name,
                    subtitle: displayType + (clusterHostMap.has(c.id) ? ` (${clusterHostMap.get(c.id)} ${t('hostResource.hostCountUnit')})` : ''),
                    raw: c,
                })
            }

            return {
                id: g.id,
                type: 'subgroup' as TreeNodeType,
                name: g.name,
                subtitle: g.code || undefined,
                children: childNodes.length > 0 ? childNodes : undefined,
                raw: g,
            }
        }

        // Root groups (no parentId) become top-level tree nodes
        const rootGroups = groups.filter(g => !g.parentId)
        const tree = rootGroups.map(g => {
            const node = buildGroupNode(g)
            return { ...node, type: 'group' as TreeNodeType }
        })

        // Collect all valid group IDs
        const validGroupIds = new Set(groups.map(g => g.id))
        // Add clusters that are not associated with any valid group as top-level nodes
        const orphanClusters = clusters.filter(c => !c.groupId || !validGroupIds.has(c.groupId))
        for (const c of orphanClusters) {
            const hostCount = clusterHostMap.get(c.id) || 0
            // Resolve cluster type name from clusterTypes by matching type field with name or code
            const clusterType = c.type ? clusterTypesHook.clusterTypes.find(ct => ct.name === c.type || ct.code === c.type) : null
            const displayType = clusterType?.name || c.type
            tree.push({
                id: c.id,
                type: 'cluster' as TreeNodeType,
                name: c.name,
                subtitle: displayType + (hostCount > 0 ? ` (${hostCount} ${t('hostResource.hostCountUnit')})` : ''),
                raw: c,
            })
        }

        // Add business services that are not associated with any valid group as top-level nodes
        const orphanBusinessServices = businessServices.filter(bs => !bs.groupId || !validGroupIds.has(bs.groupId))
        for (const bs of orphanBusinessServices) {
            const hostNames = bs.hostIds
                .map(hid => allHosts.find(h => h.id === hid)?.name)
                .filter(Boolean)
                .join(', ')
            const businessType = bs.businessTypeId ? businessTypesHook.businessTypes.find(bt => bt.id === bs.businessTypeId) : null
            tree.push({
                id: bs.id,
                type: 'business-service' as TreeNodeType,
                name: bs.name,
                subtitle: hostNames || (businessType?.name || bs.code),
                raw: bs,
            })
        }

        // Mark inheritedDisabled: a node is visually disabled if it or any ancestor group has enabled=false
        function markInherited(nodes: TreeNode[], ancestorOff: boolean) {
            for (const n of nodes) {
                const isGroup = n.type === 'group' || n.type === 'subgroup'
                const groupEnabled = isGroup && n.raw ? (n.raw as HostGroup).enabled !== false : true
                const effective = ancestorOff || !groupEnabled
                n.inheritedDisabled = effective
                if (n.children) markInherited(n.children, effective)
            }
        }
        markInherited(tree, false)

        return tree
    }, [groups, clusters, allHosts, businessServices, clusterTypesHook.clusterTypes, t])

    // Build cluster lookup for HostCard
    const clusterMap = useMemo(() => {
        const map = new Map<string, Cluster>()
        for (const c of clusters) map.set(c.id, c)
        return map
    }, [clusters])

    const handleSelect = useCallback((id: string, type: TreeNodeType) => {
        setSelected(prev => prev?.id === id && prev?.type === type ? prev : { id, type })
        setFocusedHostId(null)
        setCurrentPage(1)
    }, [])

    const handleTreeEdit = useCallback((id: string, type: TreeNodeType) => {
        if (type === 'group' || type === 'subgroup') {
            const g = groups.find(g => g.id === id)
            if (g) {
                setEditingItem({ type: 'group', data: g })
                setShowModal(true)
            }
        } else if (type === 'business-service') {
            const bs = businessServices.find(b => b.id === id)
            if (bs) {
                setEditingItem({ type: 'business-service', data: bs })
                setShowModal(true)
            }
        } else if (type === 'cluster') {
            const c = clusters.find(c => c.id === id)
            if (c) {
                setEditingItem({ type: 'cluster', data: c })
                setShowModal(true)
            }
        }
    }, [groups, clusters, businessServices])

    const handleTreeDelete = useCallback(async (id: string, type: TreeNodeType) => {
        if (type === 'group' || type === 'subgroup') {
            const confirmed = await requestConfirm({
                title: t('common.confirmTitle'),
                message: t('hostResource.confirmDeleteGroup'),
                variant: 'danger',
                confirmLabel: t('common.delete'),
            })
            if (!confirmed) return
            try {
                await deleteGroup(id)
                if (selected?.id === id) setSelected(null)
            } catch (err) {
                if ((err as any)?.status === 409) {
                    const forceConfirmed = await requestConfirm({
                        title: t('common.confirmTitle'),
                        message: t('hostResource.confirmForceDeleteGroup'),
                        variant: 'danger',
                        confirmLabel: t('common.delete'),
                    })
                    if (forceConfirmed) {
                        try {
                            await deleteGroup(id, true)
                            if (selected?.id === id) setSelected(null)
                        } catch (err2) {
                            showToast('error', err2 instanceof Error ? err2.message : 'Failed')
                        }
                    }
                } else {
                    showToast('error', err instanceof Error ? err.message : 'Failed')
                }
            }
        } else if (type === 'business-service') {
            const confirmed = await requestConfirm({
                title: t('common.confirmTitle'),
                message: t('hostResource.confirmDeleteBusinessService'),
                variant: 'danger',
                confirmLabel: t('common.delete'),
            })
            if (!confirmed) return
            try {
                await deleteBusinessService(id)
                if (selected?.id === id) setSelected(null)
            } catch (err) {
                showToast('error', err instanceof Error ? err.message : 'Failed')
            }
        } else if (type === 'cluster') {
            const confirmed = await requestConfirm({
                title: t('common.confirmTitle'),
                message: t('hostResource.confirmDeleteCluster'),
                variant: 'danger',
                confirmLabel: t('common.delete'),
            })
            if (!confirmed) return
            try {
                await deleteCluster(id)
                if (selected?.id === id) setSelected(null)
            } catch (err) {
                if ((err as any)?.status === 409) {
                    const forceConfirmed = await requestConfirm({
                        title: t('common.confirmTitle'),
                        message: t('hostResource.confirmForceDeleteCluster'),
                        variant: 'danger',
                        confirmLabel: t('common.delete'),
                    })
                    if (forceConfirmed) {
                        try {
                            await deleteCluster(id, true)
                            if (selected?.id === id) setSelected(null)
                        } catch (err2) {
                            showToast('error', err2 instanceof Error ? err2.message : 'Failed')
                        }
                    }
                } else {
                    showToast('error', err instanceof Error ? err.message : 'Failed')
                }
            }
        }
    }, [deleteGroup, deleteCluster, deleteBusinessService, selected, t, requestConfirm, showToast])

    const handleDeleteHost = useCallback(async (host: Host) => {
        const confirmed = await requestConfirm({
            title: t('common.confirmTitle'),
            message: t('hostResource.confirmDeleteHost'),
            variant: 'danger',
            confirmLabel: t('common.delete'),
        })
        if (!confirmed) return
        try {
            await deleteHost(host.id)
            if (focusedHostId === host.id) setFocusedHostId(null)
            refreshHostList()
        } catch (err) {
            showToast('error', err instanceof Error ? err.message : 'Failed')
        }
    }, [deleteHost, focusedHostId, t, refreshHostList, requestConfirm, showToast])

    const handleTestHost = useCallback(async (host: Host) => {
        setTestingId(host.id)
        setTestResults(prev => {
            const next = { ...prev }
            delete next[host.id]
            return next
        })
        try {
            const result = await testConnection(host.id)
            if (result?.success) {
                const msg = t('remoteDiagnosis.hosts.testSuccess', { latency: `${result.latency ?? ''}ms` })
                setTestResults(prev => ({ ...prev, [host.id]: { ok: true, msg } }))
            } else {
                const msg = t('remoteDiagnosis.hosts.testFailed', { error: result?.message || 'Unknown' })
                setTestResults(prev => ({ ...prev, [host.id]: { ok: false, msg } }))
            }
        } catch (err) {
            const msg = t('remoteDiagnosis.hosts.testFailed', { error: err instanceof Error ? err.message : 'Unknown' })
            setTestResults(prev => ({ ...prev, [host.id]: { ok: false, msg } }))
        } finally {
            setTestingId(null)
        }
    }, [testConnection, t])

    const handleHostCardClick = useCallback((host: Host) => {
        setFocusedHostId(prev => prev === host.id ? null : host.id)
    }, [])

    const handleToggleClusterSelect = useCallback((id: string, type: TreeNodeType) => {
        if (type === 'cluster' || type === 'business-service') {
            setSelectedClusterIds(prev => {
                const newSet = new Set(prev)
                if (newSet.has(id)) {
                    newSet.delete(id)
                } else {
                    newSet.add(id)
                }
                return newSet
            })
        }
    }, [])

    const handleSelectAllClusters = useCallback(() => {
        const allClusterIds = new Set<string>()
        function collectIds(nodes: TreeNode[]) {
            for (const node of nodes) {
                if (node.type === 'cluster' || node.type === 'business-service') {
                    allClusterIds.add(node.id)
                }
                if (node.children) collectIds(node.children)
            }
        }
        collectIds(treeData)

        if (selectedClusterIds.size === allClusterIds.size) {
            setSelectedClusterIds(new Set())
        } else {
            setSelectedClusterIds(allClusterIds)
        }
    }, [treeData, selectedClusterIds.size])

    const handleBatchDeleteClusters = useCallback(async () => {
        if (selectedClusterIds.size === 0) return

        // Separate selected clusters and business services
        const selectedClusters = clusters.filter(c => selectedClusterIds.has(c.id))
        const selectedBusinessServices = businessServices.filter(bs => selectedClusterIds.has(bs.id))

        if (selectedClusters.length === 0 && selectedBusinessServices.length === 0) return

        // Build confirmation message
        const parts: string[] = []
        if (selectedClusters.length > 0) {
            parts.push(t('hostResource.selectedClusters', { count: selectedClusters.length }))
        }
        if (selectedBusinessServices.length > 0) {
            parts.push(t('hostResource.selectedBusinessServices', { count: selectedBusinessServices.length }))
        }

        // Check for hosts in selected clusters
        let hasHosts = false
        const hostCountMap = new Map<string, number>()
        for (const h of allHosts) {
            if (h.clusterId && selectedClusterIds.has(h.clusterId)) {
                hasHosts = true
                hostCountMap.set(h.clusterId, (hostCountMap.get(h.clusterId) || 0) + 1)
            }
        }

        let confirmMessage = parts.join(', ')
        if (hasHosts) {
            confirmMessage += t('hostResource.confirmDeleteClustersWithHosts', { count: selectedClusterIds.size, totalHosts: [...hostCountMap.values()].reduce((a, b) => a + b, 0) })
        }

        const confirmed = await requestConfirm({
            title: t('common.confirmTitle'),
            message: confirmMessage,
            variant: 'danger',
            confirmLabel: t('common.delete'),
        })
        if (!confirmed) return

        // Delete selected clusters
        for (const cluster of selectedClusters) {
            try {
                await deleteCluster(cluster.id, true)
            } catch (err) {
                showToast('error', err instanceof Error ? err.message : 'Failed')
            }
        }

        // Delete selected business services
        for (const bs of selectedBusinessServices) {
            try {
                await deleteBusinessService(bs.id)
            } catch (err) {
                showToast('error', err instanceof Error ? err.message : 'Failed')
            }
        }

        setSelectedClusterIds(new Set())
    }, [selectedClusterIds, clusters, businessServices, allHosts, deleteCluster, deleteBusinessService, t, requestConfirm, showToast])

    const defaultGroupIdForCreate = selected?.type === 'group' || selected?.type === 'subgroup' ? selected.id : undefined
    const defaultClusterIdForCreate = selected?.type === 'cluster' ? selected.id : undefined

    // Client-side tree search filter
    const filteredTreeData = useMemo(() => {
        if (!treeSearch.trim()) return treeData
        const term = treeSearch.toLowerCase()
        function filterNodes(nodes: TreeNode[]): TreeNode[] {
            return nodes.reduce<TreeNode[]>((acc, node) => {
                const childMatch = node.children ? filterNodes(node.children) : []
                const selfMatch = node.name.toLowerCase().includes(term)
                    || (node.subtitle && node.subtitle.toLowerCase().includes(term))
                if (selfMatch || childMatch.length > 0) {
                    acc.push({ ...node, children: childMatch.length > 0 ? childMatch : node.children })
                }
                return acc
            }, [])
        }
        return filterNodes(treeData)
    }, [treeData, treeSearch])

    // Client-side search filter (default alphabetical ascending by name)
    const filteredHosts = useMemo(() => {
        const list = hostSearch.trim()
            ? hosts.filter(h => h.name.toLowerCase().includes(hostSearch.toLowerCase()))
            : hosts
        return [...list].sort((a, b) => a.name.localeCompare(b.name))
    }, [hosts, hostSearch])

    // Pagination
    const totalPages = Math.max(1, Math.ceil(filteredHosts.length / PAGE_SIZE))
    const safePage = Math.min(currentPage, totalPages)
    const paginatedHosts = filteredHosts.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE)

    const handleExport = useCallback(async () => {
        // 导出前主动刷新所有数据，确保包含刚新建/修改的数据
        await Promise.all([
            fetchGroups(),
            fetchAllClusters(),
            fetchAllHosts(),
            fetchClusterRelations(),
            fetchBusinessServices(),
            clusterTypesHook.fetchClusterTypes(),
            businessTypesHook.fetchBusinessTypes(),
            solutionTypesHook.fetchSolutionTypes(),
            fetchWhitelistCommands(),
            sopsHook.fetchSops(),
        ])
        exportAllAsZip({
            groups, clusters, allHosts,
            clusterRelations,
            businessServices,
            clusterTypes: clusterTypesHook.clusterTypes,
            businessTypes: businessTypesHook.businessTypes,
            solutionTypes: solutionTypesHook.solutionTypes,
            whitelistCommands,
            sops: sopsHook.sops,
        }, t)
    }, [exportAllAsZip, groups, clusters, allHosts, hostRelations, businessServices, clusterTypesHook, businessTypesHook, solutionTypesHook, whitelistCommands, sopsHook, t, fetchGroups, fetchAllClusters, fetchAllHosts, fetchHostRelations, fetchBusinessServices, fetchWhitelistCommands])

    const openCreateModal = useCallback(() => {
        setEditingItem(null)
        setShowModal(true)
    }, [])

    const openEditModal = useCallback((item: EditingItem) => {
        setEditingItem(item)
        setShowModal(true)
    }, [])

    const handleResizeStart = useCallback((e: React.MouseEvent) => {
        e.preventDefault()
        dragging.current = true
        const startX = e.clientX
        const startWidth = sidebarWidth
        const target = e.currentTarget
        target.classList.add('hr-resize-active')

        const onMouseMove = (ev: MouseEvent) => {
            if (!dragging.current) return
            const delta = ev.clientX - startX
            const next = Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, startWidth + delta))
            setSidebarWidth(next)
        }
        const onMouseUp = () => {
            dragging.current = false
            target.classList.remove('hr-resize-active')
            document.removeEventListener('mousemove', onMouseMove)
            document.removeEventListener('mouseup', onMouseUp)
            document.body.style.cursor = ''
            document.body.style.userSelect = ''
            setSidebarWidth(w => {
                localStorage.setItem(SIDEBAR_STORAGE_KEY, String(w))
                return w
            })
        }
        document.body.style.cursor = 'col-resize'
        document.body.style.userSelect = 'none'
        document.addEventListener('mousemove', onMouseMove)
        document.addEventListener('mouseup', onMouseUp)
    }, [sidebarWidth])

    const handleResizeReset = useCallback(() => {
        setSidebarWidth(SIDEBAR_DEFAULT)
        localStorage.setItem(SIDEBAR_STORAGE_KEY, String(SIDEBAR_DEFAULT))
    }, [])

    const tabs: { key: TabKey; label: string }[] = [
        { key: 'overview', label: t('hostResource.tabOverview') },
        { key: 'topology', label: t('hostResource.tabTopology') },
        { key: 'cluster-types', label: t('hostResource.tabClusterTypes') },
        { key: 'solution-types', label: t('hostResource.tabSolutionTypes') },
        { key: 'business-types', label: t('hostResource.tabBusinessTypes') },
        { key: 'sop-management', label: t('hostResource.tabSopManagement') },
        { key: 'whitelist', label: t('hostResource.tabWhitelist') },
    ]

    return (
        <div className="page-container sidebar-top-page host-resource-page">
            <PageHeader
                title={t('sidebar.hostResource')}
                action={
                    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--spacing-2, 8px)' }}>
                        {activeTab === 'overview' && (
                            <button className="btn btn-primary btn-sm" onClick={openCreateModal}>
                                + {t('hostResource.createResource')}
                            </button>
                        )}
                        <button className="btn btn-secondary btn-sm" onClick={handleExport} disabled={exporting}>
                            {exporting ? t('hostResource.exporting') : t('hostResource.export')}
                        </button>
                        <button className="btn btn-secondary btn-sm" onClick={() => setShowImportDialog(true)} disabled={importing}>
                            {t('hostResource.import')}
                        </button>
                    </div>
                }
            />

            {/* Tab Navigation */}
            <div className="config-tabs">
                {tabs.map(tab => (
                    <button
                        key={tab.key}
                        type="button"
                        className={`config-tab ${activeTab === tab.key ? 'config-tab-active' : ''}`}
                        onClick={() => setActiveTab(tab.key)}
                    >
                        {tab.label}
                    </button>
                ))}
            </div>

            {/* Tab content */}
            {activeTab === 'overview' && (
                <>

                    <div className="hr-layout-main">
                        {/* Left: Resource Tree */}
                        <div className="hr-tree-sidebar" style={{ width: sidebarWidth }}>
                            <div className="hr-tree-search">
                                <ListSearchInput
                                    value={treeSearch}
                                    placeholder={t('hostResource.searchTree')}
                                    onChange={setTreeSearch}
                                />
                            </div>
                            {activeTab === 'overview' && selectedClusterIds.size > 0 && (
                                <div style={{
                                    display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px',
                                    background: 'var(--surface-background, #f8fafc)', borderRadius: 6,
                                    marginBottom: 'var(--spacing-3)', border: '1px solid var(--border-color, #e2e8f0)'
                                }}>
                                    <input
                                        type="checkbox"
                                        checked={selectedClusterIds.size > 0}
                                        onChange={handleSelectAllClusters}
                                        style={{ cursor: 'pointer' }}
                                    />
                                    <span style={{ fontSize: '0.875rem', color: 'var(--text-primary, #1e293b)' }}>
                                        {selectedClusterIds.size > 0 ? t('common.selectedCount', { count: selectedClusterIds.size }) : t('common.selectAll')}
                                    </span>
                                    <div style={{ flex: 1 }} />
                                    <button className="btn btn-secondary btn-sm" onClick={() => setSelectedClusterIds(new Set())}>
                                        {t('common.cancel')}
                                    </button>
                                    <button
                                        className="btn btn-primary btn-sm"
                                        onClick={handleBatchDeleteClusters}
                                        style={{ background: 'var(--color-error, #ef4444)', borderColor: 'var(--color-error, #ef4444)' }}
                                    >
                                        {t('common.delete')} ({selectedClusterIds.size})
                                    </button>
                                </div>
                            )}
                            <ResourceTree
                                tree={filteredTreeData}
                                selectedId={selected?.id ?? null}
                                selectedType={selected?.type ?? null}
                                selectedIds={activeTab === 'overview' ? selectedClusterIds : undefined}
                                onSelect={handleSelect}
                                onToggleSelect={activeTab === 'overview' ? handleToggleClusterSelect : undefined}
                                onEdit={handleTreeEdit}
                                onDelete={handleTreeDelete}
                            />
                        </div>

                        {/* Resize Handle */}
                        <div
                            className="hr-resize-handle"
                            onMouseDown={handleResizeStart}
                            onDoubleClick={handleResizeReset}
                        />

                        {/* Right: Host Cards */}
                        <div className="hr-cards-area">
                            {hosts.length === 0 ? (
                                <div className="hr-empty">{t('hostResource.noHosts')}</div>
                            ) : (
                                <>
                                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 'var(--spacing-3)', marginBottom: 'var(--spacing-3)' }}>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--spacing-2, 8px)', flex: 1 }}>
                                            <ListSearchInput
                                                value={hostSearch}
                                                placeholder={t('hostResource.searchHosts')}
                                                onChange={setHostSearch}
                                            />
                                            {hostSearch && (
                                                <ListResultsMeta>
                                                    {t('common.resultsFound', { count: filteredHosts.length })}
                                                </ListResultsMeta>
                                            )}
                                        </div>
                                        {selected?.type === 'cluster' && (
                                            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--spacing-2, 8px)', flexShrink: 0 }}>
                                            </div>
                                        )}
                                    </div>
                                    <div className="hr-host-grid">
                                        {paginatedHosts.map(host => (
                                            <HostCard
                                                key={host.id}
                                                host={host}
                                                cluster={host.clusterId ? clusterMap.get(host.clusterId) : undefined}
                                                selected={focusedHostId === host.id}
                                                testing={testingId === host.id}
                                                testResult={testResults[host.id] ?? null}
                                                onClick={() => handleHostCardClick(host)}
                                                onEdit={() => openEditModal({ type: 'host', data: host })}
                                                onDelete={() => handleDeleteHost(host)}
                                                onTest={() => handleTestHost(host)}
                                            />
                                        ))}
                                    </div>
                                    {totalPages > 1 && (
                                        <div className="hr-pagination">
                                            <span className="hr-pagination-info">
                                                {t('common.showing', {
                                                    start: (safePage - 1) * PAGE_SIZE + 1,
                                                    end: Math.min(safePage * PAGE_SIZE, filteredHosts.length),
                                                    total: filteredHosts.length,
                                                })}
                                            </span>
                                            <div className="hr-pagination-controls">
                                                <button
                                                    className="hr-pagination-btn"
                                                    disabled={safePage <= 1}
                                                    onClick={() => setCurrentPage(safePage - 1)}
                                                >
                                                    {t('common.previousPage')}
                                                </button>
                                                <span className="hr-pagination-page">{safePage} / {totalPages}</span>
                                                <button
                                                    className="hr-pagination-btn"
                                                    disabled={safePage >= totalPages}
                                                    onClick={() => setCurrentPage(safePage + 1)}
                                                >
                                                    {t('common.nextPage')}
                                                </button>
                                            </div>
                                        </div>
                                    )}
                                </>
                            )}
                        </div>
                    </div>
                </>
            )}

            {activeTab === 'topology' && (
                <div className="hr-topology-area">
                    <div className="hr-topology-workbench">
                        <div className="hr-topology-graph-pane">
                            <div className="hr-topology-pane-header">
                                <div>
                                    <h3 className="hr-topology-pane-title">{t('hostResource.clusterRelationsTitle')}</h3>
                                    <p className="hr-topology-pane-subtitle">{t('hostResource.clusterRelationsSubtitle')}</p>
                                </div>
                                <SearchableSelect
                                    value={topologyGroupId}
                                    onChange={(v) => {
                                        setTopologyGroupId(v)
                                        setSelectedTopologyClusterId(null)
                                    }}
                                    options={[
                                        { value: '', label: t('hostResource.allGroups') },
                                        ...groups.filter(g => !g.parentId).map(g => ({ value: g.id, label: g.name })),
                                    ]}
                                    placeholder={t('hostResource.filterByGroup')}
                                    style={{ minWidth: 180 }}
                                />
                            </div>
                            <RelationGraph
                                data={topologyGraphData}
                                onNodeClick={(nodeId) => {
                                    const node = topologyNodeMap.get(nodeId)
                                    if (node?.nodeType === 'cluster') {
                                        setSelectedTopologyClusterId(prev => prev === nodeId ? null : nodeId)
                                    }
                                }}
                            />
                        </div>
                        <ClusterInsightPanel
                            cluster={selectedTopologyCluster}
                            hosts={selectedTopologyClusterHosts}
                            graphData={clusterGraphData}
                            viewingClusterHosts={selected?.type === 'cluster' && selected.id === selectedTopologyCluster?.id}
                            onViewClusterHosts={(clusterId) => {
                                setSelected({ id: clusterId, type: 'cluster' })
                                setFocusedHostId(null)
                                setCurrentPage(1)
                            }}
                        />
                    </div>
                </div>
            )}

            {activeTab === 'cluster-types' && (
                <ClusterTypeTab
                    clusterTypes={clusterTypesHook.clusterTypes}
                    clusters={clusters}
                    solutionTypes={solutionTypesHook.solutionTypes}
                    loading={clusterTypesHook.loading}
                    onCreate={clusterTypesHook.createClusterType}
                    onUpdate={clusterTypesHook.updateClusterType}
                    onDelete={clusterTypesHook.deleteClusterType}
                />
            )}

            {activeTab === 'solution-types' && (
                <SolutionTypeTab
                    solutionTypes={solutionTypesHook.solutionTypes}
                    loading={solutionTypesHook.loading}
                    onCreate={solutionTypesHook.createSolutionType}
                    onUpdate={solutionTypesHook.updateSolutionType}
                    onDelete={solutionTypesHook.deleteSolutionType}
                />
            )}

            {activeTab === 'business-types' && (
                <BusinessTypeTab
                    businessTypes={businessTypesHook.businessTypes}
                    businessServices={businessServices}
                    loading={businessTypesHook.loading}
                    onCreate={businessTypesHook.createBusinessType}
                    onUpdate={async (id, body) => {
                        const result = await businessTypesHook.updateBusinessType(id, body)
                        await fetchBusinessServices()
                        return result
                    }}
                    onDelete={businessTypesHook.deleteBusinessType}
                />
            )}

            {activeTab === 'sop-management' && (
                <SopsTab
                    solutionTypes={solutionTypesHook.solutionTypes}
                    sops={sopsHook.sops}
                    isLoading={sopsHook.isLoading}
                    error={sopsHook.error}
                    fetchSops={sopsHook.fetchSops}
                    createSop={sopsHook.createSop}
                    updateSop={sopsHook.updateSop}
                    deleteSop={sopsHook.deleteSop}
                />
            )}

            {activeTab === 'whitelist' && (
                <WhitelistTab
                    commands={whitelistCommands}
                    isLoading={whitelistLoading}
                    error={whitelistError}
                    fetchCommands={fetchWhitelistCommands}
                    createCommand={addWhitelistCommand}
                    updateCommand={updateWhitelistCommand}
                    deleteCommand={deleteWhitelistCommand}
                />
            )}

            {/* Create/Edit Modal */}
            {showModal && (
                <ResourceFormModal
                    key={(() => {
                        if (!editingItem) return 'create'
                        switch (editingItem.type) {
                            case 'business-service': return `bs-${editingItem.data.id}`
                            case 'cluster': return `cl-${editingItem.data.id}`
                            case 'group': return `gr-${editingItem.data.id}`
                            case 'host': return `h-${editingItem.data.id}`
                            default: return 'create'
                        }
                    })()}
                    editingItem={editingItem}
                    groups={groups}
                    clusters={clusters}
                    hosts={allHosts}
                    defaultGroupId={defaultGroupIdForCreate}
                    defaultClusterId={defaultClusterIdForCreate}
                    clusterTypes={clusterTypesHook.clusterTypes}
                    businessTypes={businessTypesHook.businessTypes}
                    businessServices={businessServices}
                    clusterRelations={clusterRelations}
                    fetchClusterRelations={fetchClusterRelations}
                    onClose={() => { setShowModal(false); setEditingItem(null) }}
                    onSaveGroup={async (data) => {
                        if (editingItem?.type === 'group') {
                            await updateGroup(editingItem.data.id, data)
                        } else {
                            await createGroup(data)
                        }
                    }}
                    onSaveCluster={async (data) => {
                        if (editingItem?.type === 'cluster') {
                            await updateCluster(editingItem.data.id, data)
                        } else {
                            await createCluster(data)
                        }
                    }}
                    onSaveBusinessService={async (data) => {
                        if (editingItem?.type === 'business-service') {
                            await updateBusinessService(editingItem.data.id, data)
                        } else {
                            await createBusinessService(data)
                        }
                    }}
                    onSaveHost={async (data) => {
                        if (editingItem?.type === 'host') {
                            await updateHost(editingItem.data.id, data as Partial<Host>)
                        } else {
                            await createHost(data as unknown as HostCreateRequest)
                        }
                        refreshHostList()
                    }}
                    onSaveClusterRelation={createClusterRelation}
                    onUpdateClusterRelation={updateClusterRelation}
                    onDeleteClusterRelation={deleteClusterRelation}
                />
            )}

            {/* Import Dialog */}
            <ImportDialog
                open={showImportDialog}
                onClose={() => setShowImportDialog(false)}
                importing={importing}
                progress={progress}
                onImport={importXlsx}
            />
        </div>
    )
}
