import { useState, useCallback, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import TypeCard from './TypeCard'
import TypeFormModal from './TypeFormModal'
import ListSearchInput from '../../../platform/ui/list/ListSearchInput'
import ListResultsMeta from '../../../platform/ui/list/ListResultsMeta'
import { useToast } from '../../../platform/providers/ToastContext'
import { useConfirmDialog } from '../../../platform/providers/ConfirmDialogContext'
import { validateAndSanitize } from '../../../../utils/inputValidation'
import type { SolutionType } from '../../../../types/host'

type Props = {
    solutionTypes: SolutionType[]
    loading: boolean
    onCreate: (body: Partial<SolutionType>) => Promise<SolutionType>
    onUpdate: (id: string, body: Partial<SolutionType>) => Promise<SolutionType>
    onDelete: (id: string) => Promise<boolean>
}

type FormData = {
    name: string
    code: string
    description: string
    color: string
    knowledge: string
}

const emptyForm: FormData = { name: '', code: '', description: '', color: '#8b5cf6', knowledge: '' }

export default function SolutionTypeTab({ solutionTypes, loading, onCreate, onUpdate, onDelete }: Props) {
    const { t } = useTranslation()
    const { showToast } = useToast()
    const { requestConfirm } = useConfirmDialog()
    const [showModal, setShowModal] = useState(false)
    const [editing, setEditing] = useState<SolutionType | null>(null)
    const [form, setForm] = useState<FormData>(emptyForm)
    const [saving, setSaving] = useState(false)
    const [searchTerm, setSearchTerm] = useState('')

    const filteredTypes = useMemo(() => {
        if (!searchTerm.trim()) return solutionTypes
        const term = searchTerm.toLowerCase()
        return solutionTypes.filter(st => st.name.toLowerCase().includes(term))
    }, [solutionTypes, searchTerm])

    const openCreate = useCallback(() => {
        setEditing(null)
        setForm(emptyForm)
        setShowModal(true)
    }, [])

    const openEdit = useCallback((item: SolutionType) => {
        setEditing(item)
        setForm({
            name: item.name,
            code: item.code,
            description: item.description,
            color: item.color,
            knowledge: item.knowledge,
        })
        setShowModal(true)
    }, [])

    const handleSave = useCallback(async () => {
        if (!form.name.trim()) return

        // XSS + length validation (consistent with import flow)
        const nameResult = validateAndSanitize(form.name, 'Name')
        if (!nameResult.valid) {
            showToast('error', t('hostResource.invalidChars'))
            return
        }
        if (nameResult.sanitized.length > 100) {
            showToast('error', t('hostResource.importErrorSolutionTypeNameTooLong', { length: String(nameResult.sanitized.length) }))
            return
        }

        const codeResult = validateAndSanitize(form.code, 'Code')
        if (!codeResult.valid) {
            showToast('error', t('hostResource.invalidChars'))
            return
        }
        if (codeResult.sanitized.length > 50) {
            showToast('error', t('hostResource.importErrorSolutionTypeCodeTooLong', { length: String(codeResult.sanitized.length) }))
            return
        }

        if (form.description && form.description.length > 500) {
            showToast('error', t('hostResource.importErrorDescriptionTooLong', { length: String(form.description.length) }))
            return
        }

        // Check for duplicate name
        const duplicateName = solutionTypes.find(st => st.name === form.name && st.id !== editing?.id)
        if (duplicateName) {
            showToast('error', t('hostResource.duplicateName', { name: form.name }))
            return
        }

        // Check for duplicate code
        const duplicateCode = solutionTypes.find(st => st.code === form.code && st.id !== editing?.id)
        if (duplicateCode) {
            showToast('error', t('hostResource.duplicateCode', { code: form.code }))
            return
        }

        setSaving(true)
        try {
            if (editing) {
                await onUpdate(editing.id, form)
            } else {
                await onCreate(form)
            }
            setShowModal(false)
        } catch (err) {
            showToast('error', err instanceof Error ? err.message : 'Failed')
        } finally {
            setSaving(false)
        }
    }, [editing, form, onCreate, onUpdate, showToast, solutionTypes, t])

    const handleDelete = useCallback(async (item: SolutionType) => {
        const confirmed = await requestConfirm({
            title: t('common.confirmTitle'),
            message: t('hostResource.confirmDeleteSolutionType'),
            variant: 'danger',
            confirmLabel: t('common.delete'),
        })
        if (confirmed) {
            try {
                await onDelete(item.id)
            } catch (err) {
                let errorMessage = err instanceof Error ? err.message : 'Failed'
                // Parse backend error message and localize it
                // Backend format: "Solution type 'XXX' is in use by: N SOP(s) - sop1, sop2, M Cluster Type(s) - ct1, ct2"
                if (errorMessage.includes("is in use by:")) {
                    const match = errorMessage.match(/Solution type '(.+)' is in use by: (.+)/)
                    if (match) {
                        const typeName = match[1]
                        const usageInfo = match[2]
                        const parts: string[] = []

                        // Split by ", " followed by digits and "Cluster Type(s)" to separate sections
                        // Input: "2 SOP(s) - Log Cleanup, Service Restart, 1 Cluster Type(s) - test11, test22"
                        // Result: ["2 SOP(s) - Log Cleanup, Service Restart", "1 Cluster Type(s) - test11, test22"]
                        const clusterTypeSplit = usageInfo.split(/, (?=\d+ Cluster Type\(s\))/)

                        // Process all tokens - could be SOP(s), Cluster Type(s), or both
                        for (const token of clusterTypeSplit) {
                            // Try SOP format first
                            const sopMatch = token.match(/(\d+) SOP\(s\) - (.+)/)
                            if (sopMatch) {
                                const count = parseInt(sopMatch[1], 10)
                                const names = sopMatch[2].trim()
                                parts.push(t('hostResource.solutionTypeUsedBySops', { count, names }))
                                continue
                            }
                            // Try Cluster Type format
                            const clusterMatch = token.match(/(\d+) Cluster Type\(s\) - (.+)/)
                            if (clusterMatch) {
                                const count = parseInt(clusterMatch[1], 10)
                                const names = clusterMatch[2].trim()
                                parts.push(t('hostResource.solutionTypeUsedByClusterTypes', { count, names }))
                            }
                        }

                        if (parts.length > 0) {
                            errorMessage = t('hostResource.solutionTypeInUseDetailed', { name: typeName, details: parts.join('；') })
                        }
                    }
                }
                showToast('error', errorMessage)
            }
        }
    }, [onDelete, t, requestConfirm, showToast])

    return (
        <div className="hr-type-tab-content">
            <div className="hr-type-tab-header">
                <span className="hr-type-tab-heading">
                    {t('hostResource.tabSolutionTypes')} ({solutionTypes.length})
                </span>
                <button className="btn btn-primary btn-sm" onClick={openCreate}>
                    + {t('hostResource.createSolutionType')}
                </button>
            </div>

            {loading && (
                <div className="hr-empty">{t('common.loading')}</div>
            )}
            {!loading && solutionTypes.length === 0 && (
                <div className="hr-type-tab-empty">
                    <div className="hr-type-tab-empty-text">{t('hostResource.noSolutionTypes')}</div>
                </div>
            )}
            {!loading && solutionTypes.length > 0 && (
                <>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 'var(--spacing-3)', marginBottom: 'var(--spacing-3)' }}>
                        <ListSearchInput
                            value={searchTerm}
                            placeholder={t('hostResource.searchSolutionTypes')}
                            onChange={setSearchTerm}
                        />
                        {searchTerm && (
                            <ListResultsMeta>
                                {t('common.resultsFound', { count: filteredTypes.length })}
                            </ListResultsMeta>
                        )}
                    </div>
                    <div className="hr-type-def-grid">
                        {filteredTypes.map(st => (
                            <TypeCard
                                key={st.id}
                                item={st}
                                onEdit={openEdit}
                                onDelete={handleDelete}
                            />
                        ))}
                    </div>
                </>
            )}

            {showModal && (
                <TypeFormModal
                    title={editing ? t('hostResource.editSolutionType') : t('hostResource.createSolutionType')}
                    form={form}
                    setForm={setForm}
                    saving={saving}
                    onSave={handleSave}
                    onClose={() => setShowModal(false)}
                    isEditing={!!editing}
                />
            )}
        </div>
    )
}
