import { useState, useCallback, useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { HostGroup, Cluster, Host, CustomAttribute, HostCreateRequest, BusinessService, ClusterType, BusinessType, ClusterRelation } from '../../../../types/host'
import { isValidIp } from '../../../../utils/ip-validation'
import { MultiSelectDropdown } from '../../../platform/ui/primitives/MultiSelectDropdown'
import { validateAndSanitize } from '../../../../utils/inputValidation'
import CustomAttributeEditor from './CustomAttributeEditor'
import TopologyNodeIcon, { type TopologyNodeKind } from './TopologyNodeIcon'
import SearchableSelect from '../../../platform/ui/forms/SearchableSelect'

type ResourceType = 'group' | 'cluster' | 'business-service' | 'host'

type EditingItem =
    | { type: 'group'; data: HostGroup }
    | { type: 'cluster'; data: Cluster }
    | { type: 'business-service'; data: BusinessService }
    | { type: 'host'; data: Host }
    | null

type Props = {
    editingItem: EditingItem
    groups: HostGroup[]
    clusters: Cluster[]
    hosts: Host[]
    defaultGroupId?: string
    defaultClusterId?: string
    clusterTypes: ClusterType[]
    businessTypes: BusinessType[]
    businessServices: BusinessService[]
    clusterRelations: ClusterRelation[]
    fetchClusterRelations: () => Promise<void>
    onClose: () => void
    onSaveGroup: (data: Partial<HostGroup>) => Promise<void>
    onSaveCluster: (data: Partial<Cluster>) => Promise<void>
    onSaveBusinessService: (data: Partial<BusinessService>) => Promise<void>
    onSaveHost: (data: HostCreateRequest | Partial<Host>) => Promise<void>
    onSaveClusterRelation: (data: Partial<ClusterRelation>) => Promise<void>
    onUpdateClusterRelation: (id: string, data: Partial<ClusterRelation>) => Promise<void>
    onDeleteClusterRelation: (id: string) => Promise<unknown>
}

export default function ResourceFormModal({
    editingItem,
    groups, clusters, hosts,
    defaultGroupId, defaultClusterId,
    clusterTypes, businessTypes, businessServices,
    clusterRelations, fetchClusterRelations,
    onClose,
    onSaveGroup, onSaveCluster, onSaveBusinessService, onSaveHost,
    onSaveClusterRelation, onUpdateClusterRelation, onDeleteClusterRelation,
}: Props) {
    const { t } = useTranslation()
    const requiredStar = <span style={{ color: 'var(--color-error, #ef4444)', marginLeft: 2 }}>*</span>
    const [selectedType, setSelectedType] = useState<ResourceType | null>(
        editingItem?.type ?? null
    )
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState<string | null>(null)

    // Auto-hide error after 3 seconds
    useEffect(() => {
        if (error) {
            const timer = setTimeout(() => setError(null), 3000)
            return () => clearTimeout(timer)
        }
    }, [error])

    // ── Group form state ──
    const [groupName, setGroupName] = useState(editingItem?.type === 'group' ? editingItem.data.name : '')
    const [groupParentId, setGroupParentId] = useState(editingItem?.type === 'group' ? (editingItem.data.parentId ?? '') : '')
    const [groupDescription, setGroupDescription] = useState(editingItem?.type === 'group' ? editingItem.data.description : '')
    const [groupCode, setGroupCode] = useState(editingItem?.type === 'group' ? (editingItem.data.code ?? '') : '')
    const [groupEnabled, setGroupEnabled] = useState(editingItem?.type === 'group' ? (editingItem.data.enabled !== false) : true)

    // ── Cluster form state ──
    const [clusterName, setClusterName] = useState(editingItem?.type === 'cluster' ? editingItem.data.name : '')
    const resolveClusterTypeName = (stored: string): string => {
        if (!stored) return ''
        if (clusterTypes.some(ct => ct.name === stored)) return stored
        return clusterTypes.find(ct => ct.code === stored)?.name ?? stored
    }
    const [clusterType, setClusterType] = useState(editingItem?.type === 'cluster' ? resolveClusterTypeName(editingItem.data.type) : '')
    useEffect(() => {
        if (editingItem?.type === 'cluster' && editingItem.data.type) {
            setClusterType(resolveClusterTypeName(editingItem.data.type))
        }
    }, [clusterTypes, editingItem])
    const [clusterTypeIsCustom, setClusterTypeIsCustom] = useState(false)
    const [clusterPurpose, setClusterPurpose] = useState(editingItem?.type === 'cluster' ? editingItem.data.purpose : '')
    const [clusterGroupId, setClusterGroupId] = useState(editingItem?.type === 'cluster' ? (editingItem.data.groupId ?? '') : (defaultGroupId ?? ''))
    const [clusterDescription, setClusterDescription] = useState(editingItem?.type === 'cluster' ? editingItem.data.description : '')

    // ── Business service form state ──
    const [bsName, setBsName] = useState(editingItem?.type === 'business-service' ? editingItem.data.name : '')
    const [bsCode, setBsCode] = useState(editingItem?.type === 'business-service' ? editingItem.data.code : '')
    const [bsGroupId, setBsGroupId] = useState(editingItem?.type === 'business-service' ? (editingItem.data.groupId ?? '') : (defaultGroupId ?? ''))
    const [bsSelectedBusinessTypeId, setBsSelectedBusinessTypeId] = useState<string>(
        editingItem?.type === 'business-service' ? (editingItem.data.businessTypeId ?? '') : ''
    )
    const [bsTags, setBsTags] = useState(editingItem?.type === 'business-service' ? (editingItem.data.tags ?? []).join(', ') : '')
    const [bsPriority, setBsPriority] = useState(editingItem?.type === 'business-service' ? editingItem.data.priority : '')
    const [bsDescription, setBsDescription] = useState(editingItem?.type === 'business-service' ? editingItem.data.description : '')

    // ── Host form state ──
    const [hostName, setHostName] = useState(editingItem?.type === 'host' ? editingItem.data.name : '')
    const [hostname, setHostname] = useState(editingItem?.type === 'host' ? (editingItem.data.hostname ?? '') : '')
    const [hostIp, setHostIp] = useState(editingItem?.type === 'host' ? editingItem.data.ip : '')
    const [hostBusinessIp, setHostBusinessIp] = useState(editingItem?.type === 'host' ? (editingItem.data.businessIp ?? '') : '')
    const [hostPort, setHostPort] = useState(editingItem?.type === 'host' ? editingItem.data.port : 22)
    const [hostOs, setHostOs] = useState(editingItem?.type === 'host' ? (editingItem.data.os ?? '') : '')
    const [hostLocation, setHostLocation] = useState(editingItem?.type === 'host' ? (editingItem.data.location ?? '') : '')
    const [hostUsername, setHostUsername] = useState(editingItem?.type === 'host' ? editingItem.data.username : '')
    const [hostAuthType, setHostAuthType] = useState<'password' | 'key'>(editingItem?.type === 'host' ? editingItem.data.authType : 'password')
    const [hostCredential, setHostCredential] = useState(editingItem?.type === 'host' ? '***' : '')
    const [hostClusterId, setHostClusterId] = useState<string>(() => {
        if (editingItem?.type === 'host') {
            return editingItem.data.clusterId ?? ''
        }
        return defaultClusterId ?? ''
    })
    const [hostPurpose, setHostPurpose] = useState(editingItem?.type === 'host' ? (editingItem.data.purpose ?? '') : '')
    const [hostBusiness, setHostBusiness] = useState(editingItem?.type === 'host' ? (editingItem.data.business ?? '') : '')
    const [hostDescription, setHostDescription] = useState(editingItem?.type === 'host' ? editingItem.data.description : '')
    const [hostCustomAttributes, setHostCustomAttributes] = useState<CustomAttribute[]>(
        editingItem?.type === 'host' ? (editingItem.data.customAttributes ?? []) : []
    )
    const [hostRole, setHostRole] = useState<'primary' | 'backup' | ''>(
        editingItem?.type === 'host' ? (editingItem.data.role ?? '') : ''
    )

    // ── Cluster relation inline editing state ──
    const [editingRelId, setEditingRelId] = useState<string | null>(null)
    const [editRelTargetId, setEditRelTargetId] = useState('')
    const [editRelDesc, setEditRelDesc] = useState('')
    const [newRelTargetIds, setNewRelTargetIds] = useState<string[]>([])
    const [newRelDesc, setNewRelDesc] = useState('')

    // Sync groupEnabled when editingItem changes (e.g. reopening same group after save)
    useEffect(() => {
        if (editingItem?.type === 'group') {
            setGroupEnabled(editingItem.data.enabled !== false)
        }
    }, [editingItem])

    // Fetch cluster relations when editing cluster or business-service
    useEffect(() => {
        if (editingItem?.type === 'cluster' || editingItem?.type === 'business-service') {
            fetchClusterRelations()
            setEditingRelId(null)
            setNewRelTargetIds([])
            setNewRelDesc('')
        }
    }, [editingItem, fetchClusterRelations])

    // Collect self + all descendant IDs when editing a group (to prevent circular refs)
    const getDescendantIds = useCallback((groupId: string): Set<string> => {
        const ids = new Set<string>()
        const queue = [groupId]
        while (queue.length > 0) {
            const current = queue.shift()!
            ids.add(current)
            for (const g of groups) {
                if (g.parentId === current && !ids.has(g.id)) {
                    queue.push(g.id)
                }
            }
        }
        return ids
    }, [groups])

    // 获取环境组的所有父组ID（包括自身）
    const getAncestorIds = useCallback((groupId: string): Set<string> => {
        const ids = new Set<string>()
        let currentGroupId: string | null = groupId
        while (currentGroupId) {
            ids.add(currentGroupId)
            const group = groups.find(g => g.id === currentGroupId)
            if (!group || !group.parentId) break
            currentGroupId = group.parentId
        }
        return ids
    }, [groups])

    // 获取环境组及其所有祖先和后代ID（完整层级范围）
    const getRelatedGroupIds = useCallback((groupId: string): Set<string> => {
        const ancestorIds = getAncestorIds(groupId)
        const descendantIds = new Set<string>()
        for (const id of ancestorIds) {
            const descendants = getDescendantIds(id)
            for (const d of descendants) {
                descendantIds.add(d)
            }
        }
        return new Set([...ancestorIds, ...descendantIds])
    }, [getAncestorIds, getDescendantIds, groups])

    const parentCandidates = useMemo(() => {
        const excludeIds = editingItem?.type === 'group' ? getDescendantIds(editingItem.data.id) : new Set<string>()
        // Allow 1st-level (no parentId) and 2nd-level (parentId points to a root group)
        return groups.filter(g => {
            if (excludeIds.has(g.id)) return false
            if (!g.parentId) return true // 1st-level group
            // 2nd-level: parentId points to a root group
            const parent = groups.find(pg => pg.id === g.parentId)
            return parent ? !parent.parentId : false
        })
    }, [groups, editingItem, getDescendantIds])

    // Helper: resolve entity display name for cluster relations
    const getEntityName = useCallback((id: string, type: string) => {
        if (type === 'cluster' || !type) {
            const c = clusters.find(cl => cl.id === id)
            return c ? c.name : id.substring(0, 8)
        }
        if (type === 'business-service') {
            const bs = businessServices.find(b => b.id === id)
            return bs ? bs.name : id.substring(0, 8)
        }
        if (type === 'host') {
            const h = hosts.find(ho => ho.id === id)
            return h ? `${h.name} (${h.ip})` : id.substring(0, 8)
        }
        return id.substring(0, 8)
    }, [clusters, businessServices, hosts])

    // Cluster relations for the current editing cluster
    const clusterEditRelations = useMemo(() => {
        if (editingItem?.type !== 'cluster') return []
        const cId = editingItem.data.id
        const hostIdSet = new Set(hosts.map(h => h.id))
        return clusterRelations.filter(r => {
            if (r.sourceId !== cId && r.targetId !== cId) return false
            // Exclude "包含" relations (cluster→host) — shown separately as read-only
            if (r.sourceType === 'cluster' && r.sourceId === cId && hostIdSet.has(r.targetId)) return false
            return true
        })
    }, [clusterRelations, editingItem, hosts])

    // Read-only "包含" relations (cluster→host) for the current editing cluster
    const clusterContainedHosts = useMemo(() => {
        if (editingItem?.type !== 'cluster') return []
        const cId = editingItem.data.id
        return clusterRelations
            .filter(r => r.sourceType === 'cluster' && r.sourceId === cId && hosts.some(h => h.id === r.targetId))
            .map(r => {
                const host = hosts.find(h => h.id === r.targetId)
                return { relId: r.id, hostId: r.targetId, name: host?.name || r.targetId, ip: host?.ip || '' }
            })
    }, [clusterRelations, editingItem, hosts])

    // Cluster relations for the current editing business service
    const bsEditRelations = useMemo(() => {
        if (editingItem?.type !== 'business-service') return []
        const bsId = editingItem.data.id
        return clusterRelations.filter(r => r.sourceId === bsId)
    }, [clusterRelations, editingItem])

    const handleAddClusterRelation = useCallback(async (sourceType: 'cluster' | 'business-service', sourceId: string) => {
        if (newRelTargetIds.length === 0) return
        const descResult = validateAndSanitize(newRelDesc, t('hostResource.relationDesc'))
        if (!descResult.valid) { setError(t('hostResource.invalidChars')); return }
        setError(null)
        const results = await Promise.allSettled(
            newRelTargetIds.map(targetId =>
                onSaveClusterRelation({
                    sourceType,
                    sourceId,
                    targetId,
                    description: descResult.sanitized,
                })
            )
        )
        const failures = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected')
        if (failures.length > 0) {
            setError(failures[0].reason instanceof Error ? failures[0].reason.message : 'Failed')
        }
        if (failures.length < newRelTargetIds.length) {
            setNewRelTargetIds([])
        }
        setNewRelDesc('')
        await fetchClusterRelations()
    }, [newRelTargetIds, newRelDesc, onSaveClusterRelation, fetchClusterRelations, t])

    const handleSaveRelationEdit = useCallback(async () => {
        if (!editingRelId) return
        const descResult = validateAndSanitize(editRelDesc, t('hostResource.relationDesc'))
        if (!descResult.valid) { setError(t('hostResource.invalidChars')); return }
        setError(null)
        try {
            await onUpdateClusterRelation(editingRelId, {
                targetId: editRelTargetId,
                description: descResult.sanitized,
            })
            setEditingRelId(null)
            await fetchClusterRelations()
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed')
        }
    }, [editingRelId, editRelTargetId, editRelDesc, onUpdateClusterRelation, fetchClusterRelations, t])

    const handleDeleteClusterRelation = useCallback(async (relId: string) => {
        setError(null)
        try {
            await onDeleteClusterRelation(relId)
            await fetchClusterRelations()
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed')
        }
    }, [onDeleteClusterRelation, fetchClusterRelations])

    const getModalTitle = () => {
        if (editingItem) {
            const typeLabels: Record<string, string> = {
                group: t('hostResource.editGroupTitle', { defaultValue: 'Edit Group' }),
                cluster: t('hostResource.editClusterTitle', { defaultValue: 'Edit Cluster' }),
                'business-service': t('hostResource.editBusinessServiceTitle', { defaultValue: 'Edit Business Service' }),
                host: t('hostResource.editHostTitle', { defaultValue: 'Edit Host' }),
            }
            return typeLabels[editingItem.type] ?? t('hostResource.editGroupTitle')
        }
        if (!selectedType) return t('hostResource.createResource')
        const typeLabels: Record<string, string> = {
            group: t('hostResource.createGroupTitle', { defaultValue: 'Create Group' }),
            cluster: t('hostResource.createClusterTitle', { defaultValue: 'Create Cluster' }),
            'business-service': t('hostResource.createBusinessServiceTitle', { defaultValue: 'Create Business Service' }),
            host: t('hostResource.createHostTitle', { defaultValue: 'Create Host' }),
        }
        return typeLabels[selectedType] ?? t('hostResource.createGroupTitle')
    }

    const handleSave = useCallback(async () => {
        setError(null)
        setSaving(true)
        try {
            if (selectedType === 'group') {
                const nameResult = validateAndSanitize(groupName, t('hostResource.groupName'))
                if (!nameResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const codeResult = validateAndSanitize(groupCode, t('hostResource.groupCode'))
                if (!codeResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const descResult = validateAndSanitize(groupDescription, t('hostResource.description'))
                if (!descResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                if (!nameResult.sanitized) { setError(t('hostResource.nameRequired')); setSaving(false); return }
                if (!codeResult.sanitized) { setError(t('hostResource.codeRequired')); setSaving(false); return }

                const editingGroupId = editingItem?.type === 'group' ? editingItem.data.id : null
                const trimmedCode = codeResult.sanitized.trim()
                const duplicateCode = groups.find(g =>
                    g.code?.toLowerCase() === trimmedCode.toLowerCase() &&
                    g.id !== editingGroupId
                )
                if (duplicateCode) {
                    setError(t('hostResource.duplicateCode', { code: trimmedCode }))
                    setSaving(false)
                    return
                }

                const duplicateName = groups.find(g =>
                    g.name?.toLowerCase() === nameResult.sanitized.toLowerCase() &&
                    g.id !== editingGroupId
                )
                if (duplicateName) {
                    setError(t('hostResource.duplicateName', { name: nameResult.sanitized }))
                    setSaving(false)
                    return
                }

                await onSaveGroup({
                    name: nameResult.sanitized,
                    code: trimmedCode,
                    parentId: groupParentId || null,
                    description: descResult.sanitized,
                    enabled: groupEnabled
                })
            } else if (selectedType === 'cluster') {
                const nameResult = validateAndSanitize(clusterName, t('hostResource.clusterName'))
                if (!nameResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const typeResult = validateAndSanitize(clusterType, t('hostResource.clusterType'))
                if (!typeResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const purposeResult = validateAndSanitize(clusterPurpose, t('hostResource.purpose'))
                if (!purposeResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const descResult = validateAndSanitize(clusterDescription, t('hostResource.description'))
                if (!descResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                if (!nameResult.sanitized) { setError(t('hostResource.nameRequired')); setSaving(false); return }
                if (!typeResult.sanitized) { setError(t('hostResource.clusterTypeRequired')); setSaving(false); return }
                if (!clusterGroupId) { setError(t('hostResource.parentGroupRequired')); setSaving(false); return }

                // Check duplicate cluster name in related group hierarchy
                const editingClusterId = editingItem?.type === 'cluster' ? editingItem.data.id : null
                const trimmedClusterName = nameResult.sanitized
                const relatedGroupIds = getRelatedGroupIds(clusterGroupId)
                const duplicateCluster = clusters.find(c => {
                    if (!c.groupId) return false
                    if (c.id === editingClusterId) return false
                    if (c.name?.toLowerCase() !== trimmedClusterName.toLowerCase()) return false
                    const clusterRelatedIds = getRelatedGroupIds(c.groupId)
                    return [...clusterRelatedIds].some(id => relatedGroupIds.has(id))
                })
                if (duplicateCluster) {
                    setError(t('hostResource.duplicateClusterName', { name: trimmedClusterName }))
                    setSaving(false)
                    return
                }

                await onSaveCluster({
                    name: nameResult.sanitized,
                    type: typeResult.sanitized,
                    purpose: purposeResult.sanitized,
                    groupId: clusterGroupId || null,
                    description: descResult.sanitized,
                })
            } else if (selectedType === 'business-service') {
                const nameResult = validateAndSanitize(bsName, t('hostResource.bsName'))
                if (!nameResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const codeResult = validateAndSanitize(bsCode, t('hostResource.bsCode'))
                if (!codeResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const descResult = validateAndSanitize(bsDescription, t('hostResource.description'))
                if (!descResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                if (!nameResult.sanitized) { setError(t('hostResource.nameRequired')); setSaving(false); return }
                if (!codeResult.sanitized) { setError(t('hostResource.codeRequired')); setSaving(false); return }
                if (!bsSelectedBusinessTypeId) { setError(t('hostResource.businessTypeRequired')); setSaving(false); return }
                if (!bsGroupId) { setError(t('hostResource.parentGroupRequired')); setSaving(false); return }

                // Check duplicate business service name in related group hierarchy
                const editingBsId = editingItem?.type === 'business-service' ? editingItem.data.id : null
                const trimmedBsName = nameResult.sanitized
                const trimmedBsCode = codeResult.sanitized
                const relatedGroupIds = getRelatedGroupIds(bsGroupId)
                const duplicateBs = businessServices.find(bs => {
                    if (!bs.groupId) return false
                    if (bs.id === editingBsId) return false
                    if (bs.name?.toLowerCase() !== trimmedBsName.toLowerCase()) return false
                    const bsRelatedIds = getRelatedGroupIds(bs.groupId)
                    return [...bsRelatedIds].some(id => relatedGroupIds.has(id))
                })
                if (duplicateBs) {
                    setError(t('hostResource.duplicateBusinessServiceName', { name: trimmedBsName }))
                    setSaving(false)
                    return
                }

                // Check duplicate business service code globally (across all groups)
                if (trimmedBsCode) {
                    const duplicateBsCode = businessServices.find(bs => {
                        if (bs.id === editingBsId) return false
                        if (!bs.code || bs.code?.toLowerCase() !== trimmedBsCode.toLowerCase()) return false
                        return true
                    })
                    if (duplicateBsCode) {
                        setError(t('hostResource.duplicateBusinessServiceCode', { code: trimmedBsCode }))
                        setSaving(false)
                        return
                    }
                }

                await onSaveBusinessService({
                    name: nameResult.sanitized,
                    code: codeResult.sanitized,
                    groupId: bsGroupId || null,
                    businessTypeId: bsSelectedBusinessTypeId || null,
                    hostIds: editingItem?.type === 'business-service' ? (editingItem.data.hostIds ?? []) : [],
                    tags: bsTags.split(',').map(s => s.trim()).filter(Boolean),
                    priority: bsPriority.trim(),
                    description: descResult.sanitized,
                    contactInfo: '',
                })
            } else if (selectedType === 'host') {
                const nameResult = validateAndSanitize(hostName, t('hostResource.hostName'))
                if (!nameResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const hostnameResult = validateAndSanitize(hostname, t('hostResource.hostname'))
                if (!hostnameResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const osResult = validateAndSanitize(hostOs, t('hostResource.os'))
                if (!osResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const locationResult = validateAndSanitize(hostLocation, t('hostResource.location'))
                if (!locationResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const purposeResult = validateAndSanitize(hostPurpose, t('hostResource.purpose'))
                if (!purposeResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const businessResult = validateAndSanitize(hostBusiness, t('hostResource.business'))
                if (!businessResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                const descResult = validateAndSanitize(hostDescription, t('hostResource.description'))
                if (!descResult.valid) { setError(t('hostResource.invalidChars')); setSaving(false); return }

                if (!nameResult.sanitized || !hostIp.trim()) { setError(t('hostResource.nameAndIpRequired')); setSaving(false); return }
                if (!hostClusterId.trim()) { setError(t('hostResource.clusterRequired')); setSaving(false); return }
                if (!isValidIp(hostIp)) { setError(t('hostResource.ipInvalid')); setSaving(false); return }
                if (hostBusinessIp.trim() && !isValidIp(hostBusinessIp)) { setError(t('hostResource.businessIpInvalid')); setSaving(false); return }
                if (hostUsername && !/^[\x00-\x7F]*$/.test(hostUsername)) {
                    setError(t('hostResource.usernameInvalidChars')); setSaving(false); return
                }
                if (hostCredential && hostCredential !== '***' && !/^[\x00-\x7F]*$/.test(hostCredential)) {
                    setError(t('hostResource.credentialInvalidChars')); setSaving(false); return
                }
                const editingHostId = editingItem?.type === 'host' ? editingItem.data.id : null
                const trimmedHostName = nameResult.sanitized
                const duplicate = hosts.some(h => h.name?.toLowerCase() === trimmedHostName.toLowerCase() && h.id !== editingHostId)
                if (duplicate) { setError(t('hostResource.duplicateName', { name: trimmedHostName })); setSaving(false); return }

                // SSH IP重复校验（在同一环境组层级中）
                const cluster = hostClusterId ? clusters.find(c => c.id === hostClusterId) : null
                const relatedGroupIds = cluster?.groupId ? getRelatedGroupIds(cluster.groupId) : new Set<string>()

                const trimmedIp = hostIp.trim()
                if (trimmedIp && relatedGroupIds.size > 0) {
                    const duplicateIpHost = hosts.find(h => {
                        if (h.id === editingHostId) return false
                        if (!h.clusterId || !h.ip) return false
                        const hostCluster = clusters.find(c => c.id === h.clusterId)
                        if (!hostCluster?.groupId) return false
                        const hostGroupIds = getRelatedGroupIds(hostCluster.groupId)
                        return [...hostGroupIds].some(id => relatedGroupIds.has(id)) && h.ip.trim() === trimmedIp
                    })
                    if (duplicateIpHost) {
                        setError(t('hostResource.duplicateIp', { ip: trimmedIp, host: duplicateIpHost.name }))
                        setSaving(false)
                        return
                    }
                }

                // Custom attribute key duplicate validation
                const validAttrs = hostCustomAttributes.filter(attr => attr.key.trim().length > 0)
                const keys = validAttrs.map(attr => attr.key.trim().toLowerCase())
                const uniqueKeys = new Set(keys)
                if (keys.length !== uniqueKeys.size) {
                    setError(t('hostResource.duplicateAttrKey'))
                    setSaving(false)
                    return
                }

                const payload: Record<string, unknown> = {
                    name: nameResult.sanitized,
                    hostname: hostnameResult.sanitized || null,
                    ip: hostIp.trim(), port: hostPort,
                    os: osResult.sanitized || null, location: locationResult.sanitized || null,
                    authType: hostAuthType, clusterId: hostClusterId || null,
                    purpose: purposeResult.sanitized || null,
                    business: businessResult.sanitized || null, description: descResult.sanitized,
                    customAttributes: validAttrs,
                    businessIp: hostBusinessIp.trim() || null,
                    role: hostRole || null,
                }
                if (hostUsername !== undefined) payload.username = hostUsername.trim()
                // Send credential if it's not the placeholder *** (including empty string to clear it)
                if (hostCredential !== '***') payload.credential = hostCredential.trim()
                await onSaveHost(payload as unknown as HostCreateRequest)
            }
            onClose()
        } catch (err) {
            let errorMessage = err instanceof Error ? err.message : 'Unknown error'
            // 特殊处理：主机编辑的用户名密码同步错误
            if (errorMessage === 'Username and credential must be provided together') {
                setError(t('hostResource.usernameCredentialMismatch'))
            } else {
                setError(errorMessage)
            }
        } finally {
            setSaving(false)
        }
    }, [selectedType, groupName, groupParentId, groupDescription, groupCode, groupEnabled, clusterName, clusterType, clusterPurpose,
        clusterGroupId, clusterDescription, hostName, hostname, hostIp, hostBusinessIp, hostPort, hostOs, hostLocation,
        hostUsername, hostAuthType, hostCredential, hostClusterId, hostPurpose, hostBusiness,
        hostDescription, hostCustomAttributes, hostRole,
        bsName, bsCode, bsGroupId, bsSelectedBusinessTypeId, bsTags, bsPriority, bsDescription,
        onSaveGroup, onSaveCluster, onSaveBusinessService, onSaveHost, onClose, t, editingItem, hosts,
        getRelatedGroupIds, clusters])

    const canSave = () => {
        if (selectedType === 'group') return groupName.trim().length > 0
        if (selectedType === 'cluster') return clusterName.trim().length > 0
        if (selectedType === 'business-service') return bsName.trim().length > 0
        if (selectedType === 'host') return hostName.trim().length > 0 && hostIp.trim().length > 0
        return false
    }

    const typeCards: { type: ResourceType; icon: string; color: string; labelKey: string; topologyKind?: TopologyNodeKind }[] = [
        { type: 'group', icon: '📁', color: 'var(--color-warning, #f59e0b)', labelKey: 'hostResource.createGroup' },
        { type: 'cluster', icon: '🖥️', color: 'var(--color-success, #10b981)', labelKey: 'hostResource.createCluster', topologyKind: 'cluster' },
        { type: 'business-service', icon: '🏢', color: '#6366f1', labelKey: 'hostResource.createBusinessService', topologyKind: 'business' },
        { type: 'host', icon: '💻', color: 'var(--color-primary, #3b82f6)', labelKey: 'hostResource.createHost', topologyKind: 'host' },
    ]

    return (
        <div className="modal-overlay">
            <div className="modal" style={{ maxWidth: selectedType === 'host' ? 640 : 520 }}>
                <div className="modal-header">
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        {!editingItem && selectedType && (
                            <button className="btn btn-secondary btn-sm" onClick={() => setSelectedType(null)} style={{ padding: '2px 8px' }}>
                                ←
                            </button>
                        )}
                        <h2 className="modal-title">{getModalTitle()}</h2>
                    </div>
                    <button className="modal-close" onClick={onClose}>&times;</button>
                </div>

                {!selectedType ? (
                    <div className="modal-body">
                        <div className="hr-type-selector">
                            {typeCards.map(card => (
                                <div
                                    key={card.type}
                                    className="hr-type-card"
                                    onClick={() => setSelectedType(card.type)}
                                >
                                    {card.topologyKind ? (
                                        <span className={`hr-type-card-icon hr-type-card-icon-svg hr-type-card-icon-svg--${card.topologyKind}`}>
                                            <TopologyNodeIcon kind={card.topologyKind} size={28} />
                                        </span>
                                    ) : (
                                        <span className="hr-type-card-icon" style={{ background: card.color }}>{card.icon}</span>
                                    )}
                                    <span className="hr-type-card-label">{t(card.labelKey)}</span>
                                </div>
                            ))}
                        </div>
                    </div>
                ) : (
                    <>
                        <div className="modal-body hr-host-modal">
                            {error && (
                                <div className="agents-alert agents-alert-error" style={{
                                    position: 'sticky',
                                    top: 0,
                                    zIndex: 10,
                                    marginBottom: 'var(--spacing-4)',
                                    borderRadius: '0 0 4px 4px',
                                    backgroundColor: '#fee',
                                    border: '1px solid #fca5a5',
                                }}>
                                    {error}
                                </div>
                            )}

                            {selectedType === 'group' && (
                                <>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.groupName')}{requiredStar}</label>
                                        <input className="form-input" value={groupName} onChange={e => setGroupName(e.target.value)} maxLength={100} />
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.groupCode')}{requiredStar}</label>
                                        <input className="form-input" value={groupCode} onChange={e => setGroupCode(e.target.value)} placeholder={t('hostResource.groupCodePlaceholder', { defaultValue: '' })} maxLength={50} />
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.parentGroup')}</label>
                                        <select className="form-input" value={groupParentId} onChange={e => setGroupParentId(e.target.value)}>
                                            <option value="">{t('hostResource.noParent')}</option>
                                            {parentCandidates.map(g => (
                                                <option key={g.id} value={g.id}>{g.name}</option>
                                            ))}
                                        </select>
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.description')}</label>
                                        <input className="form-input" value={groupDescription} onChange={e => setGroupDescription(e.target.value)} maxLength={500} />
                                    </div>
                                    {editingItem?.type === 'group' && (
                                        <div className="form-group" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                            <label className="form-label" style={{ marginBottom: 0 }}>{t('hostResource.enabled')}</label>
                                            <button
                                                type="button"
                                                className={`sop-workflow-switch ${groupEnabled ? 'is-on' : ''}`}
                                                onClick={() => setGroupEnabled(prev => !prev)}
                                            >
                                                <span className="sop-workflow-switch-thumb" />
                                            </button>
                                            <span style={{ fontSize: '0.75rem', color: groupEnabled ? 'var(--color-success, #10b981)' : 'var(--text-secondary, #64748b)' }}>
                                                {groupEnabled ? t('hostResource.enabledOn') : t('hostResource.enabledOff')}
                                            </span>
                                        </div>
                                    )}
                                </>
                            )}

                            {selectedType === 'cluster' && (
                                <>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.clusterName')}{requiredStar}</label>
                                        <input className="form-input" value={clusterName} onChange={e => setClusterName(e.target.value)} maxLength={100} />
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.clusterType')}{requiredStar}</label>
                                        <select
                                            className="form-input"
                                            value={clusterTypeIsCustom ? '__custom__' : clusterType}
                                            onChange={e => {
                                                if (e.target.value === '__custom__') {
                                                    setClusterTypeIsCustom(true)
                                                    setClusterType('')
                                                } else {
                                                    setClusterTypeIsCustom(false)
                                                    setClusterType(e.target.value)
                                                }
                                            }}
                                        >
                                            <option value="">{t('hostResource.selectClusterType')}</option>
                                            {clusterTypes.map(ct => (
                                                <option key={ct.id} value={ct.name}>{ct.name}</option>
                                            ))}
                                            <option value="__custom__">{t('hostResource.customType')}</option>
                                        </select>
                                        {clusterTypeIsCustom && (
                                            <input
                                                className="form-input"
                                                style={{ marginTop: 4 }}
                                                value={clusterType}
                                                onChange={e => setClusterType(e.target.value)}
                                                placeholder="NSLB, RCPA, KAFKA..."
                                            />
                                        )}
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.purpose')}</label>
                                        <input className="form-input" value={clusterPurpose} onChange={e => setClusterPurpose(e.target.value)} maxLength={200} />
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.parentGroup')}{requiredStar}</label>
                                        <select className="form-input" value={clusterGroupId} onChange={e => setClusterGroupId(e.target.value)}>
                                            <option value="">{t('hostResource.noParent')}</option>
                                            {groups.map(g => (
                                                <option key={g.id} value={g.id}>{g.name}</option>
                                            ))}
                                        </select>
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.description')}</label>
                                        <input className="form-input" value={clusterDescription} onChange={e => setClusterDescription(e.target.value)} maxLength={500} />
                                    </div>
                                    {/* ── Contained Hosts (read-only, driven by host cluster assignment) ── */}
                                    {editingItem?.type === 'cluster' && (
                                        <>
                                            <h4 className="hr-section-label">
                                                {t('hostResource.containedHosts')}
                                                <span style={{ fontWeight: 'normal', fontSize: '0.75rem', color: 'var(--text-secondary, #64748b)', marginLeft: 6 }}>
                                                    {t('hostResource.containedHostsTip')}
                                                </span>
                                            </h4>
                                            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                                                {clusterContainedHosts.length === 0 ? (
                                                    <div style={{ color: 'var(--text-secondary, #64748b)', fontSize: '0.8125rem' }}>
                                                        —
                                                    </div>
                                                ) : (
                                                    clusterContainedHosts.map(item => (
                                                        <div key={item.relId} style={{
                                                            display: 'flex', alignItems: 'center', gap: 6,
                                                            padding: '3px 8px', border: '1px solid var(--border-color, #e2e8f0)',
                                                            borderRadius: 4, fontSize: '0.8125rem',
                                                            background: 'var(--surface-background, #f8fafc)',
                                                        }}>
                                                            <span style={{ flex: 1 }}>{item.name}</span>
                                                            {item.ip && <span style={{ color: 'var(--text-secondary, #64748b)', fontSize: '0.75rem' }}>{item.ip}</span>}
                                                        </div>
                                                    ))
                                                )}
                                            </div>
                                        </>
                                    )}
                                    {/* ── Topology Relations (edit mode only) ── */}
                                    {editingItem?.type === 'cluster' && (
                                        <>
                                            <h4 className="hr-section-label">{t('hostResource.topology')}</h4>
                                            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                                                {clusterEditRelations.length === 0 && (
                                                    <div style={{ color: 'var(--text-secondary, #64748b)', fontSize: '0.8125rem' }}>
                                                        {t('hostResource.noSourceRelations')}
                                                    </div>
                                                )}
                                                {clusterEditRelations.map(rel => {
                                                    const isSource = rel.sourceId === editingItem.data.id
                                                    const peerId = isSource ? rel.targetId : rel.sourceId
                                                    const arrow = isSource ? '→' : '←'
                                                    const resolvedPeerType = isSource ? 'cluster' : (rel.sourceType || 'cluster')
                                                    return (
                                                    <div key={rel.id} style={{
                                                        display: 'flex', alignItems: 'center', gap: 6,
                                                        padding: '4px 8px', border: '1px solid var(--border-color, #e2e8f0)',
                                                        borderRadius: 4, fontSize: '0.8125rem',
                                                    }}>
                                                        {editingRelId === rel.id ? (
                                                            <>
                                                                <span style={{ color: 'var(--text-secondary)', flexShrink: 0 }}>{arrow}</span>
                                                                <SearchableSelect
                                                                    value={editRelTargetId}
                                                                    onChange={setEditRelTargetId}
                                                                    options={clusters.filter(c => c.id !== editingItem.data.id).map(c => ({
                                                                        value: c.id, label: c.name
                                                                    }))}
                                                                    style={{ flex: 1, fontSize: '0.75rem' }}
                                                                />
                                                                <input className="form-input" style={{ flex: 1, fontSize: '0.75rem', padding: '2px 4px' }}
                                                                    value={editRelDesc} onChange={e => setEditRelDesc(e.target.value)} maxLength={500} />
                                                                <button className="btn btn-primary btn-sm" style={{ padding: '1px 6px' }}
                                                                    onClick={handleSaveRelationEdit}>✓</button>
                                                                <button className="btn btn-secondary btn-sm" style={{ padding: '1px 6px' }}
                                                                    onClick={() => setEditingRelId(null)}>✕</button>
                                                            </>
                                                        ) : (
                                                            <>
                                                                <span style={{ color: 'var(--text-secondary)', flexShrink: 0 }}>{arrow}</span>
                                                                <span style={{ flex: 1 }}>{getEntityName(peerId, resolvedPeerType)}</span>
                                                                <span style={{ color: 'var(--text-secondary)', fontSize: '0.75rem', maxWidth: 140, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }} title={rel.description || ''}>{rel.description}</span>
                                                                <button className="hr-tree-node-action" title={t('common.edit')}
                                                                    onClick={() => { setEditingRelId(rel.id); setEditRelTargetId(rel.targetId); setEditRelDesc(rel.description) }}>
                                                                    ✎
                                                                </button>
                                                                <button className="hr-tree-node-action hr-tree-node-action-danger" title={t('common.delete')}
                                                                    onClick={() => handleDeleteClusterRelation(rel.id)}>
                                                                    ✕
                                                                </button>
                                                            </>
                                                        )}
                                                    </div>
                                                    )
                                                })}
                                                {/* Add new relation */}
                                                <div style={{
                                                    padding: '6px 8px', background: 'var(--surface-background, #f8fafc)',
                                                    borderRadius: 4, border: '1px dashed var(--border-color, #e2e8f0)',
                                                }}>
                                                    <MultiSelectDropdown
                                                        options={clusters.filter(c => c.id !== editingItem.data.id).map(c => ({ value: c.id, label: c.name }))}
                                                        selectedIds={newRelTargetIds}
                                                        onChange={setNewRelTargetIds}
                                                        placeholder={clusters.filter(c => c.id !== editingItem.data.id).length === 0 ? t('hostResource.noCluster') : t('hostResource.selectCluster', { defaultValue: t('hostResource.noCluster') })}
                                                    />
                                                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                                                        <input className="form-input" style={{ flex: 1, fontSize: '0.75rem', padding: '2px 4px' }}
                                                            placeholder={t('hostResource.relationDesc')}
                                                            value={newRelDesc} onChange={e => setNewRelDesc(e.target.value)} maxLength={500} />
                                                        <button className="btn btn-primary btn-sm" style={{ padding: '1px 8px' }}
                                                            disabled={newRelTargetIds.length === 0}
                                                            onClick={() => handleAddClusterRelation('cluster', editingItem.data.id)}>+</button>
                                                    </div>
                                                </div>
                                            </div>
                                        </>
                                    )}
                                </>
                            )}

                            {selectedType === 'business-service' && (
                                <>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.selectBusinessType')}{requiredStar}</label>
                                        {businessTypes.length > 0 ? (
                                            <select
                                                className="form-input"
                                                value={bsSelectedBusinessTypeId}
                                                onChange={e => {
                                                    const btId = e.target.value
                                                    setBsSelectedBusinessTypeId(btId)
                                                    if (btId) {
                                                        const bt = businessTypes.find(b => b.id === btId)
                                                        if (bt) {
                                                            setBsCode(bt.code)
                                                        }
                                                    } else {
                                                        setBsCode('')
                                                    }
                                                }}
                                            >
                                                <option value="">{t('hostResource.selectBusinessType')}</option>
                                                {businessTypes.map(bt => (
                                                    <option key={bt.id} value={bt.id}>{bt.name}</option>
                                                ))}
                                            </select>
                                        ) : (
                                            <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary, #64748b)' }}>
                                                {t('hostResource.noBusinessTypes')}
                                            </div>
                                        )}
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.bsName')}{requiredStar}</label>
                                        <input className="form-input" value={bsName} onChange={e => setBsName(e.target.value)} maxLength={100} />
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.bsCode')}{requiredStar}</label>
                                        <input
                                            className="form-input"
                                            value={bsCode}
                                            onChange={e => setBsCode(e.target.value)}
                                            placeholder={t('hostResource.bsCodePlaceholder', { defaultValue: '' })}
                                            maxLength={50}
                                            readOnly={editingItem?.type === 'business-service'}
                                            disabled={editingItem?.type === 'business-service'}
                                        />
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.bsPriority')}</label>
                                        <select className="form-input" value={bsPriority} onChange={e => setBsPriority(e.target.value)}>
                                            <option value="">--</option>
                                            <option value="P0">P0</option>
                                            <option value="P1">P1</option>
                                            <option value="P2">P2</option>
                                            <option value="P3">P3</option>
                                        </select>
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.bsGroup')}{requiredStar}</label>
                                        <select className="form-input" value={bsGroupId} onChange={e => setBsGroupId(e.target.value)}>
                                            <option value="">{t('hostResource.noParent')}</option>
                                            {groups.map(g => (
                                                <option key={g.id} value={g.id}>{g.name}</option>
                                            ))}
                                        </select>
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.bsTags')}</label>
                                        <input className="form-input" value={bsTags} onChange={e => setBsTags(e.target.value)} placeholder="Comma-separated tags" />
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.description')}</label>
                                        <input className="form-input" value={bsDescription} onChange={e => setBsDescription(e.target.value)} maxLength={500} />
                                    </div>
                                    {/* ── Topology Relations (edit mode only) ── */}
                                    {editingItem?.type === 'business-service' && (
                                        <>
                                            <h4 className="hr-section-label">{t('hostResource.topology')}</h4>
                                            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                                                {bsEditRelations.length === 0 && (
                                                    <div style={{ color: 'var(--text-secondary, #64748b)', fontSize: '0.8125rem' }}>
                                                        {t('hostResource.noSourceRelations')}
                                                    </div>
                                                )}
                                                {bsEditRelations.map(rel => (
                                                    <div key={rel.id} style={{
                                                        display: 'flex', alignItems: 'center', gap: 6,
                                                        padding: '4px 8px', border: '1px solid var(--border-color, #e2e8f0)',
                                                        borderRadius: 4, fontSize: '0.8125rem',
                                                    }}>
                                                        {editingRelId === rel.id ? (
                                                            <>
                                                                <span style={{ color: 'var(--text-secondary)', flexShrink: 0 }}>→</span>
                                                                <SearchableSelect
                                                                    value={editRelTargetId}
                                                                    onChange={setEditRelTargetId}
                                                                    options={clusters.map(c => ({
                                                                        value: c.id, label: c.name
                                                                    }))}
                                                                    style={{ flex: 1, fontSize: '0.75rem' }}
                                                                />
                                                                <input className="form-input" style={{ flex: 1, fontSize: '0.75rem', padding: '2px 4px' }}
                                                                    value={editRelDesc} onChange={e => setEditRelDesc(e.target.value)} maxLength={500} />
                                                                <button className="btn btn-primary btn-sm" style={{ padding: '1px 6px' }}
                                                                    onClick={handleSaveRelationEdit}>✓</button>
                                                                <button className="btn btn-secondary btn-sm" style={{ padding: '1px 6px' }}
                                                                    onClick={() => setEditingRelId(null)}>✕</button>
                                                            </>
                                                        ) : (
                                                            <>
                                                                <span style={{ color: 'var(--text-secondary)', flexShrink: 0 }}>→</span>
                                                                <span style={{ flex: 1 }}>{getEntityName(rel.targetId, 'cluster')}</span>
                                                                <span style={{ color: 'var(--text-secondary)', fontSize: '0.75rem', maxWidth: 140, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }} title={rel.description || ''}>{rel.description}</span>
                                                                <button className="hr-tree-node-action" title={t('common.edit')}
                                                                    onClick={() => { setEditingRelId(rel.id); setEditRelTargetId(rel.targetId); setEditRelDesc(rel.description) }}>
                                                                    ✎
                                                                </button>
                                                                <button className="hr-tree-node-action hr-tree-node-action-danger" title={t('common.delete')}
                                                                    onClick={() => handleDeleteClusterRelation(rel.id)}>
                                                                    ✕
                                                                </button>
                                                            </>
                                                        )}
                                                    </div>
                                                ))}
                                                {/* Add new relation */}
                                                <div style={{
                                                    padding: '6px 8px', background: 'var(--surface-background, #f8fafc)',
                                                    borderRadius: 4, border: '1px dashed var(--border-color, #e2e8f0)',
                                                }}>
                                                    <MultiSelectDropdown
                                                        options={clusters.map(c => ({ value: c.id, label: c.name }))}
                                                        selectedIds={newRelTargetIds}
                                                        onChange={setNewRelTargetIds}
                                                        placeholder={clusters.length === 0 ? t('hostResource.noCluster') : t('hostResource.selectCluster', { defaultValue: t('hostResource.noCluster') })}
                                                    />
                                                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                                                        <input className="form-input" style={{ flex: 1, fontSize: '0.75rem', padding: '2px 4px' }}
                                                            placeholder={t('hostResource.relationDesc')}
                                                            value={newRelDesc} onChange={e => setNewRelDesc(e.target.value)} maxLength={500} />
                                                        <button className="btn btn-primary btn-sm" style={{ padding: '1px 8px' }}
                                                            disabled={newRelTargetIds.length === 0}
                                                            onClick={() => handleAddClusterRelation('business-service', editingItem.data.id)}>+</button>
                                                    </div>
                                                </div>
                                            </div>
                                        </>
                                    )}
                                </>
                            )}

                            {selectedType === 'host' && (
                                <>
                                    <h4 className="hr-section-label">{t('hostResource.basicInfo')}</h4>
                                    <div className="hr-form-row">
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.hostName')}{requiredStar}</label>
                                            <input className="form-input" value={hostName} onChange={e => setHostName(e.target.value)} maxLength={100} />
                                        </div>
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.hostname')}</label>
                                            <input className="form-input" value={hostname} onChange={e => setHostname(e.target.value)} maxLength={255} />
                                        </div>
                                    </div>
                                    <div className="hr-form-row">
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.ip')}{requiredStar}</label>
                                            <input className="form-input" value={hostIp} onChange={e => setHostIp(e.target.value)} placeholder="192.168.1.100 / 2409:808c:8a:109::20" />
                                        </div>
                                        <div className="form-group" style={{ maxWidth: 120 }}>
                                            <label className="form-label">{t('hostResource.port')}</label>
                                            <input className="form-input" type="number" value={hostPort} onChange={e => setHostPort(Number(e.target.value))} />
                                        </div>
                                    </div>
                                    <div className="hr-form-row">
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.businessIp')}</label>
                                            <input className="form-input" value={hostBusinessIp} onChange={e => setHostBusinessIp(e.target.value)} placeholder={t('hostResource.businessIpPlaceholder')} />
                                        </div>
                                    </div>

                                    <h4 className="hr-section-label">{t('hostResource.systemInfo')}</h4>
                                    <div className="hr-form-row">
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.os')}</label>
                                            <input className="form-input" value={hostOs} onChange={e => setHostOs(e.target.value)} maxLength={20} />
                                        </div>
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.location')}</label>
                                            <input className="form-input" value={hostLocation} onChange={e => setHostLocation(e.target.value)} maxLength={200} />
                                        </div>
                                    </div>

                                    <h4 className="hr-section-label">{t('hostResource.authInfo')}</h4>
                                    <div className="hr-form-row">
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.username')}</label>
                                            <input className="form-input" value={hostUsername} onChange={e => setHostUsername(e.target.value)} />
                                        </div>
                                        <div className="form-group" style={{ maxWidth: 140 }}>
                                            <label className="form-label">{t('hostResource.authType')}</label>
                                            <select className="form-input" value={hostAuthType} onChange={e => setHostAuthType(e.target.value as 'password' | 'key')}>
                                                <option value="password">Password</option>
                                                <option value="key">Key</option>
                                            </select>
                                        </div>
                                    </div>
                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.credential')}</label>
                                        <input className="form-input" type="password" value={hostCredential} onChange={e => setHostCredential(e.target.value)} />
                                    </div>

                                    <h4 className="hr-section-label">{t('hostResource.businessInfo')}</h4>
                                    <div className="hr-form-row">
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.cluster')}{requiredStar}</label>
                                            <SearchableSelect
                                                value={hostClusterId}
                                                onChange={(id) => {
                                                    if (id !== hostClusterId) {
                                                        const selectedCluster = clusters.find(c => c.id === id)
                                                        const clusterTypeName = selectedCluster?.type ?? ''
                                                        // Use resolveClusterTypeName to handle both name and code matching
                                                        const resolvedTypeName = resolveClusterTypeName(clusterTypeName)
                                                        const clusterTypeObj = clusterTypes.find(ct => ct.name === resolvedTypeName)
                                                        const clusterMode = clusterTypeObj?.mode ?? 'peer'
                                                        // Clear role when switching to peer mode cluster
                                                        if (clusterMode === 'peer') {
                                                            setHostRole('')
                                                        }
                                                    }
                                                    setHostClusterId(id)
                                                }}
                                                options={clusters.map(c => ({ value: c.id, label: c.name }))}
                                                placeholder={clusters.length === 0 ? t('hostResource.noCluster') : t('hostResource.selectCluster', { defaultValue: t('hostResource.noCluster') })}
                                            />
                                        </div>
                                        {(() => {
                                            const selectedCluster = clusters.find(c => c.id === hostClusterId)
                                            const clusterTypeName = selectedCluster?.type ?? ''
                                            const clusterTypeObj = clusterTypes.find(ct => ct.name === clusterTypeName)
                                            const clusterMode = clusterTypeObj?.mode ?? 'peer'
                                            return clusterMode === 'primary-backup' ? (
                                                <div className="form-group">
                                                    <label className="form-label">{t('hostResource.hostRole')}</label>
                                                    <select className="form-input" value={hostRole} onChange={e => setHostRole(e.target.value as 'primary' | 'backup' | '')}>
                                                        <option value="">{t('hostResource.hostRoleNone')}</option>
                                                        <option value="primary">{t('hostResource.hostRolePrimary')}</option>
                                                        <option value="backup">{t('hostResource.hostRoleBackup')}</option>
                                                    </select>
                                                </div>
                                            ) : null
                                        })()}
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.purpose')}</label>
                                            <input className="form-input" value={hostPurpose} onChange={e => setHostPurpose(e.target.value)} maxLength={300} />
                                        </div>
                                    </div>
                                    <div className="hr-form-row">
                                        <div className="form-group">
                                            <label className="form-label">{t('hostResource.business')}</label>
                                            <input className="form-input" value={hostBusiness} onChange={e => setHostBusiness(e.target.value)} maxLength={200} />
                                        </div>
                                    </div>

                                    <div className="form-group">
                                        <label className="form-label">{t('hostResource.description')}</label>
                                        <input className="form-input" value={hostDescription} onChange={e => setHostDescription(e.target.value)} maxLength={500} />
                                    </div>

                                    <CustomAttributeEditor attributes={hostCustomAttributes} onChange={setHostCustomAttributes} />
                                </>
                            )}
                        </div>
                        <div className="modal-footer">
                            <button className="btn btn-secondary" onClick={onClose} disabled={saving}>{t('common.cancel')}</button>
                            <button className="btn btn-primary" onClick={handleSave} disabled={saving || !canSave()}>
                                {saving ? t('common.saving') : t('common.save')}
                            </button>
                        </div>
                    </>
                )}
            </div>
        </div>
    )
}
