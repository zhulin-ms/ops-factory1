import { useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { readXlsxFile, parseSheetToObjects } from '../../../../utils/xlsxHelper'
import { validateSheetStructure } from '../../../../utils/xlsxValidator'
import { isValidIp } from '../../../../utils/ip-validation'
import { validateAndSanitize, hasDangerousChars } from '../../../../utils/inputValidation'
import { IMPORT_METADATA } from '../../../../utils/importExportMetadata'
import type { HostGroup, Cluster, Host, HostCreateRequest, BusinessService, ClusterType, BusinessType, ClusterRelation, SolutionType } from '../../../../types/host'
import type { SopCreateRequest } from '../../../../types/sop'
import type { WhitelistCommand } from '../../../../types/commandWhitelist'

export type ImportType =
    | 'ClusterTypes'
    | 'BusinessTypes'
    | 'SolutionTypes'
    | 'HostGroups'
    | 'Clusters'
    | 'Hosts'
    | 'BusinessServices'
    | 'Relations'
    | 'SOPs'
    | 'Whitelist'

export interface ImportError {
    row: number
    code: string
    params?: Record<string, string>
}

export interface ImportProgress {
    current: number
    total: number
    phase: string
}

export interface ImportResult {
    success: number
    failed: number
    errors: ImportError[]
}

interface ImportDeps {
    fetchGroups: () => Promise<void>
    fetchAllClusters: () => Promise<void>
    fetchAllHosts: () => Promise<void>
    fetchClusterRelations: () => Promise<void>
    fetchBusinessServices: () => Promise<void>
    fetchGraph: (clusterId?: string, groupId?: string) => Promise<void>
    fetchWhitelist: () => Promise<void>
    fetchSops: () => Promise<void>

    groups: HostGroup[]
    clusters: Cluster[]
    allHosts: Host[]
    businessServices: BusinessService[]
    clusterRelations: ClusterRelation[]
    clusterTypes: ClusterType[]
    businessTypes: BusinessType[]
    solutionTypes: SolutionType[]

    createGroup: (body: Partial<HostGroup>) => Promise<HostGroup>
    updateGroup: (id: string, body: Partial<HostGroup>) => Promise<HostGroup>
    createCluster: (body: Partial<Cluster>) => Promise<Cluster>
    createHost: (body: HostCreateRequest) => Promise<Host>
    createBusinessService: (body: Partial<BusinessService>) => Promise<BusinessService>
    createClusterRelation: (body: Partial<ClusterRelation>) => Promise<unknown>
    createClusterType: (body: Partial<ClusterType>) => Promise<unknown>
    createBusinessType: (body: Partial<BusinessType>) => Promise<unknown>
    createSolutionType: (body: Partial<SolutionType>) => Promise<unknown>
    createSop: (body: SopCreateRequest) => Promise<unknown>
    addWhitelistCommand: (cmd: WhitelistCommand) => Promise<boolean>
}

export function useResourceImport(deps: ImportDeps) {
    const { t } = useTranslation()
    const [importing, setImporting] = useState(false)
    const [progress, setProgress] = useState<ImportProgress | null>(null)

    const importXlsx = useCallback(async (type: ImportType, file: File): Promise<ImportResult> => {
        try {
            const workbook = await readXlsxFile(file)
            const structureValidation = validateSheetStructure(workbook, type)

            if (!structureValidation.valid) {
                const sheetError = structureValidation.sheetErrors[0]
                return {
                    success: 0,
                    failed: 0,
                    errors: [{
                        row: 0,
                        code: 'import.invalidFile',
                        params: { message: t('hostResource.importErrorInvalidFile', { message: sheetError.params ? JSON.stringify(sheetError.params) : '' }) }
                    }]
                }
            }

            const parseResult = parseSheetToObjects(workbook, IMPORT_METADATA[type].sheetName)
            if (!parseResult.success) {
                return {
                    success: 0,
                    failed: 0,
                    errors: [{
                        row: 0,
                        code: 'import.parseError',
                        params: { message: t('hostResource.importErrorParseError', { message: parseResult.error || 'Unknown parse error' }) }
                    }]
                }
            }

            const rows = parseResult.data || []
            if (rows.length === 0) {
                return { success: 0, failed: 0, errors: [{ row: 0, code: 'import.noDataRows' }] }
            }

            setImporting(true)
            setProgress({ current: 0, total: rows.length, phase: type })

            try {
                const errors: ImportError[] = []
                let success = 0

                const groupNameToId = new Map(deps.groups.map(g => [g.name, g.id]))
                const groupCodeToId = new Map(deps.groups.map(g => [g.code ?? '', g.id]))
                const clusterGroupedKeyToId = new Map(deps.clusters.map(c => [`${c.groupId ?? ''}:${c.name}`, c.id]))
                const clusterNameToId = new Map(deps.clusters.map(c => [c.name, c.id]))
                const clusterTypeNameSet = new Set(deps.clusterTypes.map(ct => ct.name))
                const clusterTypeCodeToName = new Map(deps.clusterTypes.map(ct => [ct.code, ct.name]))
                const businessTypeNameToId = new Map(deps.businessTypes.map(bt => [bt.name, bt.id]))
                const hostNameToId = new Map(deps.allHosts.map(h => [h.name, h.id]))
                const bsNameToId = new Map(deps.businessServices.map(bs => [bs.name, bs.id]))
                const existingClusterRelationKeys = new Set(
                    deps.clusterRelations.map(r => `${r.sourceType}:${r.sourceId}->${r.targetId}:${r.description || ''}`)
                )

                // Helper functions to get related group IDs in hierarchy
                const getDescendantIds = (groupId: string, allGroups: HostGroup[]): Set<string> => {
                    const ids = new Set<string>()
                    const queue = [groupId]
                    while (queue.length > 0) {
                        const current = queue.shift()!
                        ids.add(current)
                        for (const g of allGroups) {
                            if (g.parentId === current && !ids.has(g.id)) {
                                queue.push(g.id)
                            }
                        }
                    }
                    return ids
                }

                const getAncestorIds = (groupId: string, allGroups: HostGroup[]): Set<string> => {
                    const ids = new Set<string>()
                    let currentGroupId: string | null = groupId
                    while (currentGroupId) {
                        ids.add(currentGroupId)
                        const group = allGroups.find(g => g.id === currentGroupId)
                        if (!group || !group.parentId) break
                        currentGroupId = group.parentId
                    }
                    return ids
                }

                const getRelatedGroupIds = (groupId: string, allGroups: HostGroup[]): Set<string> => {
                    const ancestorIds = getAncestorIds(groupId, allGroups)
                    const descendantIds = new Set<string>()
                    for (const id of ancestorIds) {
                        const descendants = getDescendantIds(id, allGroups)
                        for (const d of descendants) {
                            descendantIds.add(d)
                        }
                    }
                    return new Set([...ancestorIds, ...descendantIds])
                }

                const createdClusterTypeNames = new Set(deps.clusterTypes.map(ct => ct.name))
                const createdClusterTypeCodes = new Set(deps.clusterTypes.map(ct => ct.code || ''))
                const createdBusinessTypeNames = new Set(deps.businessTypes.map(bt => bt.name))
                const createdBusinessTypeCodes = new Set(deps.businessTypes.map(bt => bt.code || ''))
                const createdSolutionTypeNames = new Set(deps.solutionTypes.map(st => st.name))
                const createdSolutionTypeCodes = new Set(deps.solutionTypes.map(st => st.code || ''))
                const solutionTypeCodeSet = new Set(deps.solutionTypes.map(st => st.code))
                const createdSopNames = new Set<string>()
                const createdPatterns = new Set<string>()

                for (let i = 0; i < rows.length; i++) {
                    const row = rows[i]
                    setProgress({ current: i + 1, total: rows.length, phase: type })

                    try {
                        switch (type) {
                            case 'ClusterTypes': {
                                const typeName = row.name?.trim() || ''
                                if (!typeName) {
                                    errors.push({ row: i + 2, code: 'import.clusterTypeNameRequired' })
                                    continue
                                }
                                const nameResult = validateAndSanitize(typeName, 'Name')
                                if (!nameResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Name' } })
                                    continue
                                }
                                if (nameResult.sanitized.length > 100) {
                                    errors.push({ row: i + 2, code: 'import.clusterTypeNameTooLong', params: { length: String(nameResult.sanitized.length) } })
                                    continue
                                }
                                const typeCode = row.code?.trim() || ''
                                if (!typeCode) {
                                    errors.push({ row: i + 2, code: 'import.clusterTypeCodeRequired' })
                                    continue
                                }
                                const codeResult = validateAndSanitize(typeCode, 'Code')
                                if (!codeResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Code' } })
                                    continue
                                }
                                if (codeResult.sanitized.length > 50) {
                                    errors.push({ row: i + 2, code: 'import.clusterTypeCodeTooLong', params: { length: String(codeResult.sanitized.length) } })
                                    continue
                                }
                                if (row.description) {
                                    const description = row.description?.trim() || ''
                                    if (description.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(description.length) } })
                                        continue
                                    }
                                }
                                if (row.knowledge) {
                                    const knowledge = row.knowledge?.trim() || ''
                                    if (knowledge.length > 2000) {
                                        errors.push({ row: i + 2, code: 'import.knowledgeTooLong', params: { length: String(knowledge.length), max: '2000' } })
                                        continue
                                    }
                                }
                                if (row.clusterMode && row.clusterMode !== 'Peer' && row.clusterMode !== 'Primary-Backup') {
                                    errors.push({ row: i + 2, code: 'import.clusterTypeInvalidMode', params: { mode: row.clusterMode } })
                                    continue
                                }
                                // Validate solution type if provided
                                const solutionType = row.solutionType?.trim() || 'universal'
                                if (solutionType !== 'universal' && !solutionTypeCodeSet.has(solutionType)) {
                                    errors.push({ row: i + 2, code: 'import.clusterTypeSolutionTypeNotFound', params: { solution: solutionType } })
                                    continue
                                }
                                if (createdClusterTypeNames.has(nameResult.sanitized) || createdClusterTypeCodes.has(codeResult.sanitized)) {
                                    // Determine if it's a name or code duplicate
                                    if (createdClusterTypeCodes.has(codeResult.sanitized) && !createdClusterTypeNames.has(nameResult.sanitized)) {
                                        errors.push({ row: i + 2, code: 'import.duplicateCode', params: { type: 'ClusterType', code: codeResult.sanitized } })
                                    } else {
                                        errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'ClusterType', name: nameResult.sanitized } })
                                    }
                                    continue
                                }
                                // Validate envVariables key uniqueness (case-insensitive)
                                const envVars = row.envVariables
                                    ? row.envVariables.split(';').filter(Boolean).map(pair => {
                                        const eq = pair.indexOf('=')
                                        return { key: eq > 0 ? pair.slice(0, eq) : pair, value: eq > 0 ? pair.slice(eq + 1) : '' }
                                    })
                                    : undefined
                                if (envVars && envVars.length > 0) {
                                    const seenKeys = new Set<string>()
                                    let hasDuplicateKey = false
                                    for (const ev of envVars) {
                                        const lowerKey = ev.key.toLowerCase()
                                        if (seenKeys.has(lowerKey)) {
                                            errors.push({ row: i + 2, code: 'import.envVarDuplicateKey', params: { key: ev.key } })
                                            hasDuplicateKey = true
                                            break
                                        }
                                        seenKeys.add(lowerKey)
                                    }
                                    if (hasDuplicateKey) continue
                                }
                                await deps.createClusterType({
                                    name: nameResult.sanitized,
                                    code: codeResult.sanitized,
                                    description: row.description ? row.description.trim() : '',
                                    knowledge: row.knowledge || '',
                                    color: row.typeColor || '',
                                    mode: (() => {
                                        if (row.clusterMode === 'Peer') return 'peer'
                                        if (row.clusterMode === 'Primary-Backup') return 'primary-backup'
                                        return undefined
                                    })(),
                                    commandPrefix: row.commandPrefix || '',
                                    envVariables: envVars,
                                    solutionType: solutionType,
                                })
                                createdClusterTypeNames.add(nameResult.sanitized)
                                createdClusterTypeCodes.add(codeResult.sanitized)
                                success++
                                break
                            }

                            case 'BusinessTypes': {
                                const typeName = row.name?.trim() || ''
                                if (!typeName) {
                                    errors.push({ row: i + 2, code: 'import.businessTypeNameRequired' })
                                    continue
                                }
                                const nameResult = validateAndSanitize(typeName, 'Name')
                                if (!nameResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Name' } })
                                    continue
                                }
                                if (nameResult.sanitized.length > 100) {
                                    errors.push({ row: i + 2, code: 'import.businessTypeNameTooLong', params: { length: String(nameResult.sanitized.length) } })
                                    continue
                                }
                                const typeCode = row.code?.trim() || ''
                                if (!typeCode) {
                                    errors.push({ row: i + 2, code: 'import.businessTypeCodeRequired' })
                                    continue
                                }
                                const codeResult = validateAndSanitize(typeCode, 'Code')
                                if (!codeResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Code' } })
                                    continue
                                }
                                if (codeResult.sanitized.length > 50) {
                                    errors.push({ row: i + 2, code: 'import.businessTypeCodeTooLong', params: { length: String(codeResult.sanitized.length) } })
                                    continue
                                }
                                if (row.description) {
                                    const description = row.description?.trim() || ''
                                    if (description.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(description.length) } })
                                        continue
                                    }
                                }
                                if (row.knowledge) {
                                    const knowledge = row.knowledge?.trim() || ''
                                    if (knowledge.length > 2000) {
                                        errors.push({ row: i + 2, code: 'import.knowledgeTooLong', params: { length: String(knowledge.length), max: '2000' } })
                                        continue
                                    }
                                }
                                if (createdBusinessTypeNames.has(nameResult.sanitized) || createdBusinessTypeCodes.has(codeResult.sanitized)) {
                                    // Determine if it's a name or code duplicate
                                    if (createdBusinessTypeCodes.has(codeResult.sanitized) && !createdBusinessTypeNames.has(nameResult.sanitized)) {
                                        errors.push({ row: i + 2, code: 'import.duplicateCode', params: { type: 'BusinessType', code: codeResult.sanitized } })
                                    } else {
                                        errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'BusinessType', name: nameResult.sanitized } })
                                    }
                                    continue
                                }
                                await deps.createBusinessType({
                                    name: nameResult.sanitized,
                                    code: codeResult.sanitized,
                                    description: row.description ? row.description.trim() : '',
                                    color: row.typeColor || '',
                                    knowledge: row.knowledge || '',
                                })
                                createdBusinessTypeNames.add(nameResult.sanitized)
                                createdBusinessTypeCodes.add(codeResult.sanitized)
                                success++
                                break
                            }

                            case 'SolutionTypes': {
                                const stName = row.name?.trim() || ''
                                if (!stName) {
                                    errors.push({ row: i + 2, code: 'import.solutionTypeNameRequired' })
                                    continue
                                }
                                const nameResult = validateAndSanitize(stName, 'Name')
                                if (!nameResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Name' } })
                                    continue
                                }
                                if (nameResult.sanitized.length > 100) {
                                    errors.push({ row: i + 2, code: 'import.solutionTypeNameTooLong', params: { length: String(nameResult.sanitized.length) } })
                                    continue
                                }
                                const stCode = row.code?.trim() || ''
                                if (!stCode) {
                                    errors.push({ row: i + 2, code: 'import.solutionTypeCodeRequired' })
                                    continue
                                }
                                const codeResult = validateAndSanitize(stCode, 'Code')
                                if (!codeResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Code' } })
                                    continue
                                }
                                if (codeResult.sanitized.length > 50) {
                                    errors.push({ row: i + 2, code: 'import.solutionTypeCodeTooLong', params: { length: String(codeResult.sanitized.length) } })
                                    continue
                                }
                                if (row.description) {
                                    const description = row.description?.trim() || ''
                                    if (description.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(description.length) } })
                                        continue
                                    }
                                }
                                if (row.knowledge) {
                                    const knowledge = row.knowledge?.trim() || ''
                                    if (knowledge.length > 2000) {
                                        errors.push({ row: i + 2, code: 'import.knowledgeTooLong', params: { length: String(knowledge.length), max: '2000' } })
                                        continue
                                    }
                                }
                                if (createdSolutionTypeNames.has(nameResult.sanitized) || createdSolutionTypeCodes.has(codeResult.sanitized)) {
                                    // Determine if it's a name or code duplicate
                                    if (createdSolutionTypeCodes.has(codeResult.sanitized) && !createdSolutionTypeNames.has(nameResult.sanitized)) {
                                        errors.push({ row: i + 2, code: 'import.duplicateCode', params: { type: 'SolutionType', code: codeResult.sanitized } })
                                    } else {
                                        errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'SolutionType', name: nameResult.sanitized } })
                                    }
                                    continue
                                }
                                await deps.createSolutionType({
                                    name: nameResult.sanitized,
                                    code: codeResult.sanitized,
                                    description: row.description ? row.description.trim() : '',
                                    color: row.typeColor || '',
                                    knowledge: row.knowledge || '',
                                })
                                createdSolutionTypeNames.add(nameResult.sanitized)
                                createdSolutionTypeCodes.add(codeResult.sanitized)
                                success++
                                break
                            }

                            case 'HostGroups': {
                                const groupName = row.name?.trim() || ''
                                if (!groupName) {
                                    errors.push({ row: i + 2, code: 'import.hostGroupNameRequired' })
                                    continue
                                }
                                const nameResult = validateAndSanitize(groupName, 'Name')
                                if (!nameResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Name' } })
                                    continue
                                }
                                if (nameResult.sanitized.length > 100) {
                                    errors.push({ row: i + 2, code: 'import.hostGroupNameTooLong', params: { length: String(nameResult.sanitized.length) } })
                                    continue
                                }
                                const groupCode = row.code?.trim() || ''
                                if (!groupCode) {
                                    errors.push({ row: i + 2, code: 'import.hostGroupCodeRequired' })
                                    continue
                                }
                                const codeResult = validateAndSanitize(groupCode, 'Code')
                                if (!codeResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Code' } })
                                    continue
                                }
                                if (codeResult.sanitized.length > 50) {
                                    errors.push({ row: i + 2, code: 'import.hostGroupCodeTooLong', params: { length: String(codeResult.sanitized.length) } })
                                    continue
                                }
                                const trimmedCode = codeResult.sanitized.trim()
                                if (groupCodeToId.has(trimmedCode)) {
                                    errors.push({ row: i + 2, code: 'import.duplicateCode', params: { type: 'HostGroup', code: trimmedCode } })
                                    continue
                                }
                                if (row.description) {
                                    const descResult = validateAndSanitize(row.description, 'Description')
                                    if (!descResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Description' } })
                                        continue
                                    }
                                    if (descResult.sanitized.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(descResult.sanitized.length) } })
                                        continue
                                    }
                                }
                                if (groupNameToId.has(nameResult.sanitized)) {
                                    errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'HostGroup', name: nameResult.sanitized } })
                                    continue
                                }
                                if (row.enabled) {
                                    const enabledValue = String(row.enabled).trim().toUpperCase()
                                    if (enabledValue !== 'TRUE' && enabledValue !== 'FALSE') {
                                        errors.push({ row: i + 2, code: 'import.hostGroupEnabledInvalid', params: { value: String(row.enabled) } })
                                        continue
                                    }
                                }
                                // Parent group check: must exist if provided
                                let parentId: string | undefined
                                if (row.parentGroup) {
                                    const parentGroupName = String(row.parentGroup).trim()
                                    parentId = groupNameToId.get(parentGroupName) || groupCodeToId.get(parentGroupName)
                                    if (!parentId) {
                                        errors.push({ row: i + 2, code: 'import.parentGroupNotFound', params: { parentGroup: parentGroupName } })
                                        continue
                                    }
                                }
                                const created = await deps.createGroup({
                                    name: nameResult.sanitized,
                                    code: codeResult.sanitized,
                                    description: row.description ? validateAndSanitize(row.description, 'Description').sanitized : '',
                                    enabled: row.enabled ? String(row.enabled).toUpperCase() === 'TRUE' : true,
                                    parentId,
                                })
                                groupNameToId.set(nameResult.sanitized, created.id)
                                groupCodeToId.set(codeResult.sanitized, created.id)
                                success++
                                break
                            }

                            case 'Clusters': {
                                const clusterName = row.name?.trim() || ''
                                if (!clusterName) {
                                    errors.push({ row: i + 2, code: 'import.clusterNameRequired' })
                                    continue
                                }
                                const nameResult = validateAndSanitize(clusterName, 'Name')
                                if (!nameResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Name' } })
                                    continue
                                }
                                if (nameResult.sanitized.length > 100) {
                                    errors.push({ row: i + 2, code: 'import.clusterNameTooLong', params: { length: String(nameResult.sanitized.length) } })
                                    continue
                                }
                                // Validate required field: type (cluster type)
                                const clusterType = row.type?.trim() || ''
                                if (!clusterType) {
                                    errors.push({ row: i + 2, code: 'import.clusterTypeRequired' })
                                    continue
                                }
                                const typeResult = validateAndSanitize(clusterType, 'Type')
                                if (!typeResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Type' } })
                                    continue
                                }
                                if (row.purpose) {
                                    const purposeResult = validateAndSanitize(row.purpose, 'Purpose')
                                    if (!purposeResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Purpose' } })
                                        continue
                                    }
                                    if (purposeResult.sanitized.length > 200) {
                                        errors.push({ row: i + 2, code: 'import.purposeTooLong', params: { length: String(purposeResult.sanitized.length) } })
                                        continue
                                    }
                                }
                                if (row.description) {
                                    const descResult = validateAndSanitize(row.description, 'Description')
                                    if (!descResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Description' } })
                                        continue
                                    }
                                    if (descResult.sanitized.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(descResult.sanitized.length) } })
                                        continue
                                    }
                                }
                                // Validate required field: group (parent host group)
                                const groupName = row.group?.trim() || ''
                                if (!groupName) {
                                    errors.push({ row: i + 2, code: 'import.clusterGroupRequired' })
                                    continue
                                }
                                const groupId = groupNameToId.get(groupName) || groupCodeToId.get(groupName)
                                if (!groupId) {
                                    errors.push({ row: i + 2, code: 'import.groupNotFound', params: { group: groupName } })
                                    continue
                                }

                                // Check duplicate cluster name in related group hierarchy
                                const trimmedClusterName = nameResult.sanitized
                                if (groupId) {
                                    const relatedGroupIds = getRelatedGroupIds(groupId, deps.groups)
                                    // Check against existing clusters
                                    const duplicateExisting = deps.clusters.find(c => {
                                        if (!c.groupId || c.name?.toLowerCase() !== trimmedClusterName.toLowerCase()) return false
                                        const cRelatedIds = getRelatedGroupIds(c.groupId, deps.groups)
                                        return [...cRelatedIds].some(id => relatedGroupIds.has(id))
                                    })
                                    if (duplicateExisting) {
                                        errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'Cluster', name: trimmedClusterName } })
                                        continue
                                    }
                                    // Check against already created clusters in this import
                                    for (const [key, _id] of clusterGroupedKeyToId) {
                                        const [cGroupId, cName] = key.split(':')
                                        if (cName.toLowerCase() === trimmedClusterName.toLowerCase() && cGroupId) {
                                            const cRelatedIds = getRelatedGroupIds(cGroupId, deps.groups)
                                            if ([...cRelatedIds].some(id => relatedGroupIds.has(id))) {
                                                errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'Cluster', name: trimmedClusterName } })
                                                continue
                                            }
                                        }
                                    }
                                }
                                const clusterKey = `${groupId}:${trimmedClusterName}`
                                // Validate cluster type exists
                                let typeName = clusterType
                                if (!clusterTypeNameSet.has(typeName)) {
                                    const mappedName = clusterTypeCodeToName.get(typeName)
                                    if (mappedName) {
                                        typeName = mappedName
                                    } else {
                                        errors.push({ row: i + 2, code: 'import.clusterTypeNotFound', params: { type: typeName } })
                                        continue
                                    }
                                }
                                const created = await deps.createCluster({
                                    name: nameResult.sanitized,
                                    type: typeName,
                                    purpose: row.purpose ? validateAndSanitize(row.purpose, 'Purpose').sanitized : '',
                                    groupId,
                                    description: row.description ? validateAndSanitize(row.description, 'Description').sanitized : '',
                                })
                                clusterGroupedKeyToId.set(clusterKey, created.id)
                                clusterNameToId.set(nameResult.sanitized, created.id)
                                success++
                                break
                            }

                            case 'Hosts': {
                                const hostName = row.name?.trim() || ''
                                const hostIp = row.ip?.trim() || ''
                                const hostUsername = row.username?.trim() || ''
                                if (!hostName) {
                                    errors.push({ row: i + 2, code: 'import.hostNameRequired' })
                                    continue
                                }
                                const nameResult = validateAndSanitize(hostName, 'Name')
                                if (!nameResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Name' } })
                                    continue
                                }
                                if (hostName.length > 100) {
                                    errors.push({ row: i + 2, code: 'import.hostNameTooLong', params: { length: String(hostName.length) } })
                                    continue
                                }
                                if (!hostIp) {
                                    errors.push({ row: i + 2, code: 'import.hostIpRequired' })
                                    continue
                                }
                                if (!isValidIp(hostIp)) {
                                    errors.push({ row: i + 2, code: 'import.hostIpInvalid', params: { ip: hostIp } })
                                    continue
                                }
                                if (row.businessIp && !isValidIp(row.businessIp)) {
                                    errors.push({ row: i + 2, code: 'import.businessIpInvalid', params: { ip: row.businessIp } })
                                    continue
                                }
                                // Validate port if provided
                                if (row.port) {
                                    const portStr = String(row.port).trim()
                                    if (portStr === '') {
                                        // Empty string after trim is treated as no port provided
                                    } else if (!/^\d+$/.test(portStr)) {
                                        errors.push({ row: i + 2, code: 'import.hostPortInvalid', params: { port: portStr } })
                                        continue
                                    } else {
                                        const portNum = parseInt(portStr, 10)
                                        if (portNum < 1 || portNum > 65535) {
                                            errors.push({ row: i + 2, code: 'import.hostPortOutOfRange', params: { port: portStr } })
                                            continue
                                        }
                                    }
                                }
                                if (hostUsername && !/^[\x00-\x7F]*$/.test(hostUsername)) {
                                    errors.push({ row: i + 2, code: 'import.usernameInvalidChars' })
                                    continue
                                }
                                if (row.credential && row.credential !== '***' && !/^[\x00-\x7F]*$/.test(row.credential)) {
                                    errors.push({ row: i + 2, code: 'import.credentialInvalidChars' })
                                    continue
                                }
                                // Username and credential must both be provided or both be empty
                                const hostCredential = row.credential?.trim() || ''
                                const hasUsername = hostUsername.length > 0
                                const hasCredential = hostCredential.length > 0 && hostCredential !== '***'
                                if (hasUsername !== hasCredential) {
                                    errors.push({ row: i + 2, code: 'import.usernameCredentialMismatch' })
                                    continue
                                }
                                if (row.hostname) {
                                    const hostnameResult = validateAndSanitize(row.hostname, 'Hostname')
                                    if (!hostnameResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Hostname' } })
                                        continue
                                    }
                                }
                                if (row.os) {
                                    const osResult = validateAndSanitize(row.os, 'OS')
                                    if (!osResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'OS' } })
                                        continue
                                    }
                                }
                                if (row.location) {
                                    const locationResult = validateAndSanitize(row.location, 'Location')
                                    if (!locationResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Location' } })
                                        continue
                                    }
                                }
                                if (row.purpose) {
                                    const purposeResult = validateAndSanitize(row.purpose, 'Purpose')
                                    if (!purposeResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Purpose' } })
                                        continue
                                    }
                                }
                                if (row.business) {
                                    const businessResult = validateAndSanitize(row.business, 'Business')
                                    if (!businessResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Business' } })
                                        continue
                                    }
                                }
                                if (row.description) {
                                    const descResult = validateAndSanitize(row.description, 'Description')
                                    if (!descResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Description' } })
                                        continue
                                    }
                                }
                                if (hostNameToId.has(hostName)) {
                                    errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'Host', name: hostName } })
                                    continue
                                }
                                // Validate cluster is required
                                if (!row.cluster || !row.cluster.trim()) {
                                    errors.push({ row: i + 2, code: 'import.clusterRequired' })
                                    continue
                                }
                                const clusterId = row.cluster
                                    ? clusterNameToId.get(row.cluster)
                                    : undefined
                                if (!clusterId) {
                                    errors.push({ row: i + 2, code: 'import.clusterNotFound', params: { cluster: row.cluster } })
                                    continue
                                }
                                if (row.authType) {
                                    const authTypeValue = String(row.authType).trim().toLowerCase()
                                    if (authTypeValue !== 'password' && authTypeValue !== 'key') {
                                        errors.push({ row: i + 2, code: 'import.hostAuthTypeInvalid', params: { value: String(row.authType) } })
                                        continue
                                    }
                                }
                                const roleValue = row.role as string | undefined
                                if (roleValue) {
                                    const roleTrimmed = String(roleValue).trim().toLowerCase()
                                    if (roleTrimmed !== 'primary' && roleTrimmed !== 'backup') {
                                        errors.push({ row: i + 2, code: 'import.hostRoleInvalid', params: { value: String(roleValue) } })
                                        continue
                                    }
                                }
                                const customAttributes = row.customAttributes
                                    ? String(row.customAttributes).split(';').filter(Boolean).map(pair => {
                                        const eq = pair.indexOf('=')
                                        return { key: eq >= 0 ? pair.slice(0, eq).trim() : pair.trim(), value: eq >= 0 ? pair.slice(eq + 1).trim() : '' }
                                    }).filter(a => a.key)
                                    : undefined
                                if (customAttributes && customAttributes.length > 0) {
                                    const seenKeys = new Set<string>()
                                    let hasDuplicateKey = false
                                    for (const attr of customAttributes) {
                                        const lowerKey = attr.key.toLowerCase()
                                        if (seenKeys.has(lowerKey)) {
                                            errors.push({ row: i + 2, code: 'import.customAttrDuplicateKey', params: { key: attr.key } })
                                            hasDuplicateKey = true
                                            break
                                        }
                                        seenKeys.add(lowerKey)
                                    }
                                    if (hasDuplicateKey) continue
                                }
                                const created = await deps.createHost({
                                    name: nameResult.sanitized,
                                    ip: hostIp,
                                    port: row.port ? parseInt(row.port, 10) : 22,
                                    username: hostUsername,
                                    authType: (row.authType === 'key' ? 'key' : 'password') as 'password' | 'key',
                                    credential: row.credential || '',
                                    hostname: row.hostname ? validateAndSanitize(row.hostname, 'Hostname').sanitized : undefined,
                                    businessIp: row.businessIp || undefined,
                                    os: row.os ? validateAndSanitize(row.os, 'OS').sanitized : undefined,
                                    location: row.location ? validateAndSanitize(row.location, 'Location').sanitized : undefined,
                                    business: row.business ? validateAndSanitize(row.business, 'Business').sanitized : undefined,
                                    clusterId,
                                    purpose: row.purpose ? validateAndSanitize(row.purpose, 'Purpose').sanitized : undefined,
                                    role: (roleValue === 'primary' || roleValue === 'backup') ? roleValue : undefined,
                                    description: row.description ? validateAndSanitize(row.description, 'Description').sanitized : undefined,
                                    customAttributes,
                                })
                                hostNameToId.set(row.name, created.id)
                                success++
                                break
                            }

                            case 'BusinessServices': {
                                const serviceName = row.name?.trim() || ''
                                if (!serviceName) {
                                    errors.push({ row: i + 2, code: 'import.businessServiceNameRequired' })
                                    continue
                                }
                                const nameResult = validateAndSanitize(serviceName, 'Name')
                                if (!nameResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Name' } })
                                    continue
                                }
                                if (nameResult.sanitized.length > 100) {
                                    errors.push({ row: i + 2, code: 'import.businessServiceNameTooLong', params: { length: String(nameResult.sanitized.length) } })
                                    continue
                                }
                                const codeValue = row.code?.trim() || ''
                                if (!codeValue) {
                                    errors.push({ row: i + 2, code: 'import.businessServiceCodeRequired' })
                                    continue
                                }
                                const codeResult = validateAndSanitize(codeValue, 'Code')
                                if (!codeResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Code' } })
                                    continue
                                }
                                if (codeResult.sanitized.length > 50) {
                                    errors.push({ row: i + 2, code: 'import.businessServiceCodeTooLong', params: { length: String(codeResult.sanitized.length) } })
                                    continue
                                }
                                if (row.description) {
                                    const descResult = validateAndSanitize(row.description, 'Description')
                                    if (!descResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Description' } })
                                        continue
                                    }
                                    if (descResult.sanitized.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(descResult.sanitized.length) } })
                                        continue
                                    }
                                }
                                if (bsNameToId.has(nameResult.sanitized)) {
                                    errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'BusinessService', name: nameResult.sanitized } })
                                    continue
                                }
                                if (!row.group?.trim()) {
                                    errors.push({ row: i + 2, code: 'import.businessServiceGroupRequired' })
                                    continue
                                }
                                const groupId = row.group
                                    ? (groupNameToId.get(row.group) || groupCodeToId.get(row.group))
                                    : undefined
                                if (row.priority?.trim()) {
                                    const validPriorities = ['P0', 'P1', 'P2', 'P3']
                                    if (!validPriorities.includes(row.priority.trim())) {
                                        errors.push({ row: i + 2, code: 'import.businessServicePriorityInvalid', params: { priority: row.priority } })
                                        continue
                                    }
                                }
                                const businessType = row.businessType?.trim() || ''
                                if (!businessType) {
                                    errors.push({ row: i + 2, code: 'import.businessTypeRequired' })
                                    continue
                                }
                                const businessTypeId = businessTypeNameToId.get(businessType)
                                if (!businessTypeId) {
                                    errors.push({ row: i + 2, code: 'import.businessTypeNotFound', params: { type: businessType } })
                                    continue
                                }
                                if (!groupId && row.group) {
                                    errors.push({ row: i + 2, code: 'import.groupNotFound', params: { group: row.group } })
                                    continue
                                }

                                // Check duplicate business service name in related group hierarchy
                                const trimmedBsName = nameResult.sanitized
                                if (groupId) {
                                    const relatedGroupIds = getRelatedGroupIds(groupId, deps.groups)
                                    // Check against existing business services
                                    const duplicateExisting = deps.businessServices.find(bs => {
                                        if (!bs.groupId || bs.name?.toLowerCase() !== trimmedBsName.toLowerCase()) return false
                                        const bsRelatedIds = getRelatedGroupIds(bs.groupId, deps.groups)
                                        return [...bsRelatedIds].some(id => relatedGroupIds.has(id))
                                    })
                                    if (duplicateExisting) {
                                        errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'BusinessService', name: trimmedBsName } })
                                        continue
                                    }
                                    // Check against already created business services in this import
                                    for (const [bsName, _bsId] of bsNameToId) {
                                        if (bsName.toLowerCase() === trimmedBsName.toLowerCase()) {
                                            const bs = deps.businessServices.find(b => b.name === bsName)
                                            if (bs && bs.groupId) {
                                                const bsRelatedIds = getRelatedGroupIds(bs.groupId, deps.groups)
                                                if ([...bsRelatedIds].some(id => relatedGroupIds.has(id))) {
                                                    errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'BusinessService', name: trimmedBsName } })
                                                    continue
                                                }
                                            }
                                        }
                                    }
                                }
                                const created = await deps.createBusinessService({
                                    name: nameResult.sanitized,
                                    code: row.code ? validateAndSanitize(row.code, 'Code').sanitized : '',
                                    groupId,
                                    businessTypeId,
                                    description: row.description ? validateAndSanitize(row.description, 'Description').sanitized : '',
                                    tags: row.tags ? row.tags.split(';').map(t => t.trim()).filter(Boolean) : [],
                                    priority: row.priority || '',
                                })
                                bsNameToId.set(nameResult.sanitized, created.id)
                                success++
                                break
                            }

                            case 'Relations': {
                                // 1. 解析并校验 sourceNodeType
                                const sourceTypeValue = row.sourceNodeType?.trim() || ''
                                const normalizedSourceType = sourceTypeValue.toLowerCase().replace(/[-_\s]+/g, '-')
                                let sourceType: 'cluster' | 'business-service' | undefined

                                if (normalizedSourceType === 'cluster' || normalizedSourceType === '集群') {
                                    sourceType = 'cluster'
                                } else if (normalizedSourceType === 'business-service' || normalizedSourceType === 'businessservice' || normalizedSourceType === '业务服务') {
                                    sourceType = 'business-service'
                                } else {
                                    errors.push({ row: i + 2, code: 'import.sourceNodeTypeInvalid', params: { value: sourceTypeValue } })
                                    continue
                                }

                                // 2. 校验 destNodeType（必须为 Cluster）
                                const destTypeValue = row.destNodeType?.trim() || ''
                                const normalizedDestType = destTypeValue.toLowerCase().replace(/[-_\s]+/g, '-')
                                if (normalizedDestType !== 'cluster' && normalizedDestType !== '集群') {
                                    errors.push({ row: i + 2, code: 'import.destNodeTypeInvalid', params: { value: destTypeValue } })
                                    continue
                                }

                                // 3. 根据 sourceType 查找 sourceId
                                let sourceId: string | undefined
                                if (sourceType === 'cluster') {
                                    sourceId = clusterNameToId.get(row.sourceNode)
                                } else if (sourceType === 'business-service') {
                                    sourceId = bsNameToId.get(row.sourceNode)
                                }

                                if (!sourceId) {
                                    errors.push({ row: i + 2, code: 'import.sourceNodeNotFound', params: { node: row.sourceNode } })
                                    continue
                                }

                                // 4. destNode 必须是集群
                                const destClusterId = clusterNameToId.get(row.destNode)
                                if (!destClusterId) {
                                    errors.push({ row: i + 2, code: 'import.targetClusterNotFound', params: { cluster: row.destNode } })
                                    continue
                                }

                                // 5. description 长度校验
                                if (row.description && String(row.description).length > 500) {
                                    errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(row.description.length) } })
                                    continue
                                }

                                // 6. 查重（允许同一 source/target 不同 description）
                                const relationKey = `${sourceType}:${sourceId}->${destClusterId}:${row.description || ''}`
                                if (existingClusterRelationKeys.has(relationKey)) {
                                    errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'Relation', name: `${row.sourceNode} -> ${row.destNode}` } })
                                    continue
                                }
                                existingClusterRelationKeys.add(relationKey)

                                await deps.createClusterRelation({
                                    sourceType,
                                    sourceId,
                                    targetId: destClusterId,
                                    description: row.description || '',
                                })
                                success++
                                break
                            }

                            case 'SOPs': {
                                const sopName = row.name?.trim() || ''
                                if (!sopName) {
                                    errors.push({ row: i + 2, code: 'import.sopNameRequired' })
                                    continue
                                }
                                const nameResult = validateAndSanitize(sopName, 'Name')
                                if (!nameResult.valid) {
                                    errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Name' } })
                                    continue
                                }
                                if (nameResult.sanitized.length > 100) {
                                    errors.push({ row: i + 2, code: 'import.sopNameTooLong', params: { length: String(nameResult.sanitized.length) } })
                                    continue
                                }
                                if (row.version) {
                                    const versionResult = validateAndSanitize(row.version, 'Version')
                                    if (!versionResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Version' } })
                                        continue
                                    }
                                    if (versionResult.sanitized.length > 50) {
                                        errors.push({ row: i + 2, code: 'import.sopVersionTooLong', params: { length: String(versionResult.sanitized.length) } })
                                        continue
                                    }
                                }
                                if (row.mode && row.mode !== 'structured' && row.mode !== 'natural_language') {
                                    errors.push({ row: i + 2, code: 'import.sopInvalidMode', params: { mode: row.mode } })
                                    continue
                                }
                                if (row.description) {
                                    const description = row.description?.trim() || ''
                                    if (description.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(description.length) } })
                                        continue
                                    }
                                }
                                if (row.triggerCondition) {
                                    const triggerCondition = row.triggerCondition?.trim() || ''
                                    if (triggerCondition.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.sopTriggerConditionTooLong', params: { length: String(triggerCondition.length) } })
                                        continue
                                    }
                                }
                                if (row.stepsDescription) {
                                    const stepsDescription = row.stepsDescription?.trim() || ''
                                    if (stepsDescription.length > 1000) {
                                        errors.push({ row: i + 2, code: 'import.sopStepsDescriptionTooLong', params: { length: String(stepsDescription.length) } })
                                        continue
                                    }
                                }
                                if (createdSopNames.has(nameResult.sanitized)) {
                                    errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'SOP', name: nameResult.sanitized } })
                                    continue
                                }
                                if (row.enabled) {
                                    const enabledValue = String(row.enabled).trim().toUpperCase()
                                    if (enabledValue !== 'TRUE' && enabledValue !== 'FALSE') {
                                        errors.push({ row: i + 2, code: 'import.sopEnabledInvalid', params: { value: String(row.enabled) } })
                                        continue
                                    }
                                }
                                let targetSolutionValue = row.targetSolution?.trim() || 'universal'
                                if (targetSolutionValue !== 'universal') {
                                    if (!solutionTypeCodeSet.has(targetSolutionValue)) {
                                        errors.push({ row: i + 2, code: 'import.sopTargetSolutionNotFound', params: { solution: targetSolutionValue } })
                                        continue
                                    }
                                }
                                const sopData = {
                                    name: nameResult.sanitized,
                                    description: row.description ? validateAndSanitize(row.description, 'Description').sanitized : '',
                                    version: row.version || '',
                                    triggerCondition: row.triggerCondition ? validateAndSanitize(row.triggerCondition, 'TriggerCondition').sanitized : '',
                                    enabled: row.enabled ? String(row.enabled).toUpperCase() !== 'FALSE' : true,
                                    stepsDescription: row.stepsDescription ? validateAndSanitize(row.stepsDescription, 'StepsDescription').sanitized : '',
                                    targetSolution: targetSolutionValue,
                                }
                                await deps.createSop(sopData)
                                createdSopNames.add(nameResult.sanitized)
                                success++
                                break
                            }

                            case 'Whitelist': {
                                const pattern = row.pattern?.trim() || ''
                                if (!pattern) {
                                    errors.push({ row: i + 2, code: 'import.whitelistPatternRequired' })
                                    continue
                                }
                                if (createdPatterns.has(pattern)) {
                                    errors.push({ row: i + 2, code: 'import.duplicate', params: { type: 'Whitelist', name: pattern } })
                                    continue
                                }
                                // Check for XSS characters (allow '/' for paths, but block < > " ' & `)
                                if (hasDangerousChars(pattern)) {
                                    errors.push({ row: i + 2, code: 'import.whitelistInvalidPattern', params: { pattern: pattern } })
                                    continue
                                }
                                if (!/^[a-zA-Z0-9_\-./\s]+$/.test(pattern)) {
                                    errors.push({ row: i + 2, code: 'import.whitelistInvalidPattern', params: { pattern: pattern } })
                                    continue
                                }
                                if (pattern.length > 500) {
                                    errors.push({ row: i + 2, code: 'import.whitelistPatternTooLong', params: { length: String(pattern.length) } })
                                    continue
                                }
                                if (row.description) {
                                    const descResult = validateAndSanitize(row.description, 'Description')
                                    if (!descResult.valid) {
                                        errors.push({ row: i + 2, code: 'import.invalidChars', params: { field: 'Description' } })
                                        continue
                                    }
                                    if (descResult.sanitized.length > 500) {
                                        errors.push({ row: i + 2, code: 'import.descriptionTooLong', params: { length: String(descResult.sanitized.length) } })
                                        continue
                                    }
                                }
                                const enabledValue = row.enabled ? String(row.enabled).trim().toUpperCase() : ''
                                if (enabledValue && enabledValue !== 'TRUE' && enabledValue !== 'FALSE') {
                                    errors.push({ row: i + 2, code: 'import.whitelistEnabledInvalid', params: { value: String(row.enabled) } })
                                    continue
                                }
                                await deps.addWhitelistCommand({
                                    pattern: pattern,
                                    description: row.description ? validateAndSanitize(row.description, 'Description').sanitized : '',
                                    enabled: enabledValue !== 'FALSE',
                                })
                                createdPatterns.add(pattern)
                                success++
                                break
                            }
                            default:
                                break
                        }
                    } catch (err) {
                        const msg = err instanceof Error ? err.message : String(err)
                        if (msg === 'Command whitelist entry conflict' || msg.includes('already exists') || msg.includes('conflict')) {
                            // 根据导入类型设置名称字段
                            let name = ''
                            if (type === 'Whitelist') name = row.pattern
                            else if (type === 'SOPs') name = row.name
                            else name = row.name || row.pattern || ''
                            errors.push({ row: i + 2, code: 'import.duplicate', params: { type: type, name: name } })
                        } else {
                            errors.push({ row: i + 2, code: 'import.rowError', params: { message: msg } })
                        }
                    }
                }

                try {
                    await Promise.all([
                        deps.fetchGroups(),
                        deps.fetchAllClusters(),
                        deps.fetchAllHosts(),
                        deps.fetchClusterRelations(),
                        deps.fetchBusinessServices(),
                        deps.fetchGraph(),
                        deps.fetchWhitelist(),
                        deps.fetchSops(),
                    ])
                } catch {}

                setImporting(false)
                setProgress(null)
                return { success, failed: errors.length, errors }
            } catch (err) {
                const msg = err instanceof Error ? err.message : String(err)
                setImporting(false)
                setProgress(null)
                return {
                    success: 0,
                    failed: rows.length,
                    errors: [{ row: 0, code: 'import.importFailed', params: { message: t('hostResource.importErrorImportFailed', { message: msg }) } }]
                }
            }
        } catch (err) {
            const msg = err instanceof Error ? err.message : String(err)
            return {
                success: 0,
                failed: 0,
                errors: [{ row: 0, code: 'import.fileReadError', params: { message: t('hostResource.importErrorFileReadError', { message: msg }) } }]
            }
        }
    }, [deps])

    return { importing, progress, importXlsx }
}
