import { FieldMetadata, ResourceImportMetadata } from '../types/importExport'
import type { ImportType } from '../types/importExport'
import { hasXssChars } from './inputValidation'

export const IMPORT_METADATA: Record<ImportType, ResourceImportMetadata> = {
    ClusterTypes: {
        resourceType: 'ClusterTypes',
        sheetName: 'Cluster Types',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'name', labelKey: 'field_clusterTypes_name', enLabel: 'Cluster Type Name', zhLabel: '集群类型名称', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 100) return { valid: false, error: 'Exceeds maximum length of 100' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 100 } } },
            { name: 'code', labelKey: 'field_clusterTypes_code', enLabel: 'Cluster Type Code', zhLabel: '集群类型编码', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 50) return { valid: false, error: 'Exceeds maximum length of 50' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 50 } } },
            { name: 'description', labelKey: 'field_clusterTypes_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 500) return { valid: false, error: 'Exceeds maximum length of 500' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 500 } } },
            { name: 'typeColor', labelKey: 'field_clusterTypes_typeColor', enLabel: 'Color', zhLabel: '标识颜色', required: false, validation: { type: 'string' } },
            { name: 'knowledge', labelKey: 'field_clusterTypes_knowledge', enLabel: 'Knowledge', zhLabel: '常识', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (value.length > 2000) return { valid: false, error: 'Exceeds maximum length of 2000' }
                return { valid: true }
            }, description: 'validationMaxLength', descriptionParams: { max: 2000 } } },
            { name: 'solutionType', labelKey: 'field_clusterTypes_solutionType', enLabel: 'Solution Type Code', zhLabel: '解决方案类型编码', required: false, validation: { type: 'string' } },
            { name: 'clusterMode', labelKey: 'field_clusterTypes_clusterMode', enLabel: 'Cluster Mode', zhLabel: '集群模式（可选：Peer/Primary-Backup）', required: false, validation: { type: 'enum', enumValues: ['Peer', 'Primary-Backup'] } },
            { name: 'commandPrefix', labelKey: 'field_clusterTypes_commandPrefix', enLabel: 'Command Prefix', zhLabel: '命令前缀', required: false, validation: { type: 'string' } },
            { name: 'envVariables', labelKey: 'field_clusterTypes_envVariables', enLabel: 'Environment Variables', zhLabel: '环境变量', required: false, validation: { type: 'array', separator: ';' } },
        ],
        sampleData: [
            {
                name: 'Kubernetes Cluster',
                code: 'k8s',
                description: 'Kubernetes container orchestration cluster',
                typeColor: '#FF6B6B',
                knowledge: 'container-orchestration',
                clusterMode: 'Peer',
                commandPrefix: 'kubectl',
                envVariables: 'KUBECONFIG=/etc/kubernetes/config;CLUSTER_NAME=prod',
                solutionType: 'universal',
            },
        ],
    },
    BusinessTypes: {
        resourceType: 'BusinessTypes',
        sheetName: 'Business Types',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'name', labelKey: 'field_businessTypes_name', enLabel: 'Business Type Name', zhLabel: '业务类型名称', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 100) return { valid: false, error: 'Exceeds maximum length of 100' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 100 } } },
            { name: 'code', labelKey: 'field_businessTypes_code', enLabel: 'Business Type Code', zhLabel: '业务类型编码', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 50) return { valid: false, error: 'Exceeds maximum length of 50' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 50 } } },
            { name: 'description', labelKey: 'field_businessTypes_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (value.length > 500) return { valid: false, error: 'Exceeds maximum length of 500' }
                return { valid: true }
            }, description: 'validationMaxLength', descriptionParams: { max: 500 } } },
            { name: 'typeColor', labelKey: 'field_businessTypes_typeColor', enLabel: 'Color', zhLabel: '标识颜色', required: false, validation: { type: 'string' } },
            { name: 'knowledge', labelKey: 'field_businessTypes_knowledge', enLabel: 'Knowledge', zhLabel: '常识', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (value.length > 2000) return { valid: false, error: 'Exceeds maximum length of 2000' }
                return { valid: true }
            }, description: 'validationMaxLength', descriptionParams: { max: 2000 } } },
        ],
        sampleData: [
            {
                name: 'Web Application',
                code: 'web-app',
                description: 'Web application service',
                typeColor: '#6366f1',
                knowledge: 'web-services',
            },
        ],
    },
    SolutionTypes: {
        resourceType: 'SolutionTypes',
        sheetName: 'Solution Types',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'name', labelKey: 'field_solutionTypes_name', enLabel: 'Solution Type Name', zhLabel: '解决方案类型名称', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 100) return { valid: false, error: 'Exceeds maximum length of 100' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 100 } } },
            { name: 'code', labelKey: 'field_solutionTypes_code', enLabel: 'Solution Type Code', zhLabel: '解决方案类型编码', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 50) return { valid: false, error: 'Exceeds maximum length of 50' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 50 } } },
            { name: 'description', labelKey: 'field_solutionTypes_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (value.length > 500) return { valid: false, error: 'Exceeds maximum length of 500' }
                return { valid: true }
            }, description: 'validationMaxLength', descriptionParams: { max: 500 } } },
            { name: 'typeColor', labelKey: 'field_solutionTypes_typeColor', enLabel: 'Color', zhLabel: '标识颜色', required: false, validation: { type: 'string' } },
            { name: 'knowledge', labelKey: 'field_solutionTypes_knowledge', enLabel: 'Knowledge', zhLabel: '常识', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (value.length > 2000) return { valid: false, error: 'Exceeds maximum length of 2000' }
                return { valid: true }
            }, description: 'validationMaxLength', descriptionParams: { max: 2000 } } },
        ],
        sampleData: [
            {
                name: 'CRM System',
                code: 'crm',
                description: 'Customer relationship management solution',
                typeColor: '#8b5cf6',
                knowledge: 'crm-best-practices',
            },
        ],
    },
    HostGroups: {
        resourceType: 'HostGroups',
        sheetName: 'Host Groups',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'name', labelKey: 'field_hostGroups_name', enLabel: 'Host Group Name', zhLabel: '环境组名称', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 100) return { valid: false, error: 'Exceeds maximum length of 100' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 100 } } },
            { name: 'code', labelKey: 'field_hostGroups_code', enLabel: 'Host Group Code', zhLabel: '环境组编码', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 50) return { valid: false, error: 'Exceeds maximum length of 50' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 50 } } },
            { name: 'parentGroup', labelKey: 'field_hostGroups_parentGroup', enLabel: 'Parent Group', zhLabel: '父主机组', required: false, validation: { type: 'string' } },
            { name: 'description', labelKey: 'field_hostGroups_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 500) return { valid: false, error: 'Exceeds maximum length of 500' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 500 } } },
            { name: 'enabled', labelKey: 'field_hostGroups_enabled', enLabel: 'Enabled', zhLabel: '启用状态', required: false, validation: { type: 'enum', enumValues: ['TRUE', 'FALSE'] } },
        ],
        sampleData: [
            {
                name: 'Production Servers',
                code: 'prod-servers',
                parentGroup: 'Data Center A',
                description: 'Production environment server group',
                enabled: 'TRUE',
            },
            {
                name: 'Backup Servers',
                code: 'backup-servers',
                parentGroup: 'Data Center A',
                description: 'Backup server group',
                enabled: 'TRUE',
            },
        ],
    },
    Clusters: {
        resourceType: 'Clusters',
        sheetName: 'Clusters',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'name', labelKey: 'field_clusters_name', enLabel: 'Cluster Name', zhLabel: '集群名称', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 100) return { valid: false, error: 'Exceeds maximum length of 100' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 100 } } },
            { name: 'type', labelKey: 'field_clusters_type', enLabel: 'Cluster Type', zhLabel: '集群类型', required: true, validation: { type: 'string' } },
            { name: 'purpose', labelKey: 'field_clusters_purpose', enLabel: 'Purpose', zhLabel: '用途', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 200) return { valid: false, error: 'Exceeds maximum length of 200' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 200 } } },
            { name: 'group', labelKey: 'field_clusters_group', enLabel: 'Host Group', zhLabel: '所属环境组', required: true, validation: { type: 'string' } },
            { name: 'description', labelKey: 'field_clusters_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 500) return { valid: false, error: 'Exceeds maximum length of 500' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 500 } } },
        ],
        sampleData: [
            {
                name: 'Web Cluster 01',
                type: 'Kubernetes',
                purpose: 'Web application service',
                group: 'Production Servers',
                description: 'Production environment web cluster',
            },
            {
                name: 'Database Cluster 01',
                type: 'MySQL',
                purpose: 'Database service',
                group: 'Production Servers',
                description: 'Production environment database cluster',
            },
        ],
    },
    Hosts: {
        resourceType: 'Hosts',
        sheetName: 'Hosts',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'name', labelKey: 'field_hosts_name', enLabel: 'Host Name', zhLabel: '主机名称', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 100) return { valid: false, error: 'Exceeds maximum length of 100' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 100 } } },
            { name: 'hostname', labelKey: 'field_hosts_hostname', enLabel: 'System Hostname', zhLabel: '系统主机名', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 255) return { valid: false, error: 'Exceeds maximum length of 255' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 255 } } },
            { name: 'ip', labelKey: 'field_hosts_ip', enLabel: 'SSH IP Address', zhLabel: 'SSH IP 地址', required: true, validation: { type: 'ip' } },
            { name: 'port', labelKey: 'field_hosts_port', enLabel: 'Port', zhLabel: '端口', required: false, validation: { type: 'number' } },
            { name: 'businessIp', labelKey: 'field_hosts_businessIp', enLabel: 'Business IP Address', zhLabel: '业务 IP 地址', required: false, validation: { type: 'ip' } },
            { name: 'os', labelKey: 'field_hosts_os', enLabel: 'Operating System', zhLabel: '操作系统', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 20) return { valid: false, error: 'Exceeds maximum length of 20' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 20 } } },
            { name: 'location', labelKey: 'field_hosts_location', enLabel: 'Location', zhLabel: '部署位置', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 200) return { valid: false, error: 'Exceeds maximum length of 200' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 200 } } },
            { name: 'username', labelKey: 'field_hosts_username', enLabel: 'Username', zhLabel: '用户名', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (value && !/^[\x00-\x7F]*$/.test(value)) {
                    return { valid: false, error: 'Username must contain only ASCII characters' }
                }
                return { valid: true }
            } } },
            { name: 'authType', labelKey: 'field_hosts_authType', enLabel: 'Auth Type', zhLabel: '认证类型（可选：password/key）', required: false, validation: { type: 'enum', enumValues: ['password', 'key'] } },
            { name: 'credential', labelKey: 'field_hosts_credential', enLabel: 'Credential', zhLabel: '凭证', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (value && value !== '***' && !/^[\x00-\x7F]*$/.test(value)) {
                    return { valid: false, error: 'Credential must contain only ASCII characters' }
                }
                return { valid: true }
            } } },
            { name: 'business', labelKey: 'field_hosts_business', enLabel: 'Business', zhLabel: '业务', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 200) return { valid: false, error: 'Exceeds maximum length of 200' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 200 } } },
            { name: 'cluster', labelKey: 'field_hosts_cluster', enLabel: 'Cluster', zhLabel: '所属集群', required: false, validation: { type: 'string' } },
            { name: 'purpose', labelKey: 'field_hosts_purpose', enLabel: 'Purpose', zhLabel: '用途', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 300) return { valid: false, error: 'Exceeds maximum length of 300' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 300 } } },
            { name: 'role', labelKey: 'field_hosts_role', enLabel: 'Role', zhLabel: '角色（可选：primary/backup）', required: false, validation: { type: 'enum', enumValues: ['primary', 'backup'] } },
            { name: 'description', labelKey: 'field_hosts_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 500) return { valid: false, error: 'Exceeds maximum length of 500' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 500 } } },
            { name: 'customAttributes', labelKey: 'field_hosts_customAttributes', enLabel: 'Custom Attributes', zhLabel: '自定义属性', required: false, validation: { type: 'custom', description: 'validationKeyValuePairs' } },
        ],
        sampleData: [
            {
                name: 'Web Server 01',
                hostname: 'web-01.example.com',
                ip: '192.168.1.10',
                port: '22',
                businessIp: '10.0.1.10',
                os: 'CentOS 7.9',
                location: 'Data Center A - Rack 01',
                username: 'opsuser',
                authType: 'key',
                credential: '***',
                business: 'Web Application',
                cluster: 'Web Cluster 01',
                purpose: 'Web service',
                role: 'primary',
                description: 'Production environment web server',
                customAttributes: 'env=production;team=ops',
            },
            {
                name: 'DB Server 01',
                hostname: 'db-01.example.com',
                ip: '192.168.1.20',
                port: '22',
                businessIp: '10.0.1.20',
                os: 'Ubuntu 20.04',
                location: 'Data Center A - Rack 02',
                username: 'dbadmin',
                authType: 'password',
                credential: '***',
                business: 'Database',
                cluster: 'Database Cluster 01',
                purpose: 'Database service',
                role: 'primary',
                description: 'Production environment database server',
                customAttributes: 'env=production;team=dba',
            },
        ],
    },
    BusinessServices: {
        resourceType: 'BusinessServices',
        sheetName: 'Business Services',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'businessType', labelKey: 'field_businessServices_businessType', enLabel: 'Business Type', zhLabel: '业务类型', required: true, validation: { type: 'string' } },
            { name: 'name', labelKey: 'field_businessServices_name', enLabel: 'Business Name', zhLabel: '业务名称', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 100) return { valid: false, error: 'Exceeds maximum length of 100' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 100 } } },
            { name: 'code', labelKey: 'field_businessServices_code', enLabel: 'Business Code', zhLabel: '业务编码', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 50) return { valid: false, error: 'Exceeds maximum length of 50' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 50 } } },
            { name: 'priority', labelKey: 'field_businessServices_priority', enLabel: 'Priority', zhLabel: '优先级', required: false, validation: { type: 'enum', enumValues: ['P0', 'P1', 'P2', 'P3'] } },
            { name: 'group', labelKey: 'field_businessServices_group', enLabel: 'Group', zhLabel: '所属分组', required: true, validation: { type: 'string' } },
            { name: 'tags', labelKey: 'field_businessServices_tags', enLabel: 'Tags', zhLabel: '标签', required: false, validation: { type: 'array', separator: ';' } },
            { name: 'description', labelKey: 'field_businessServices_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 500) return { valid: false, error: 'Exceeds maximum length of 500' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 500 } } },
        ],
        sampleData: [
            {
                businessType: 'Web Application',
                name: 'Order Service',
                code: 'order-service',
                priority: 'P1',
                group: 'Production Servers',
                tags: 'core;payment',
                description: 'Order processing service',
            },
            {
                businessType: 'Web Application',
                name: 'User Service',
                code: 'user-service',
                priority: 'P1',
                group: 'Production Servers',
                tags: 'core;auth',
                description: 'User management service',
            },
        ],
    },
    Relations: {
        resourceType: 'Relations',
        sheetName: 'Relations',
        descriptionSheetName: '字段说明',
        fields: [
            {
                name: 'sourceNode',
                labelKey: 'field_relations_sourceNode',
                enLabel: 'Source Node',
                zhLabel: '源节点',
                required: true,
                validation: { type: 'string' },
            },
            {
                name: 'destNode',
                labelKey: 'field_relations_destNode',
                enLabel: 'Target Node',
                zhLabel: '目标节点',
                required: true,
                validation: { type: 'string' },
            },
            {
                name: 'sourceNodeType',
                labelKey: 'field_relations_sourceNodeType',
                enLabel: 'Source Node Type',
                zhLabel: '源节点类型',
                required: true,
                validation: {
                    type: 'enum',
                    enumValues: ['Cluster', 'Business Service'],
                },
            },
            {
                name: 'destNodeType',
                labelKey: 'field_relations_destNodeType',
                enLabel: 'Target Node Type',
                zhLabel: '目标节点类型',
                required: true,
                validation: {
                    type: 'enum',
                    enumValues: ['Cluster'],
                },
            },
            {
                name: 'description',
                labelKey: 'field_relations_description',
                enLabel: 'Description',
                zhLabel: '描述',
                required: false,
                validation: { type: 'string', maxLength: 500 },
            },
        ],
        sampleData: [
            {
                sourceNode: 'Web Cluster 01',
                destNode: 'Database Cluster 01',
                sourceNodeType: 'Cluster',
                destNodeType: 'Cluster',
                description: 'Web cluster accesses database cluster',
            },
            {
                sourceNode: 'Order Service',
                destNode: 'Web Cluster 01',
                sourceNodeType: 'Business Service',
                destNodeType: 'Cluster',
                description: 'Order service entry to web cluster',
            },
        ],
    },
    SOPs: {
        resourceType: 'SOPs',
        sheetName: 'SOPs',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'name', labelKey: 'field_sops_name', enLabel: 'SOP Name', zhLabel: 'SOP 名称', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 100) return { valid: false, error: 'Exceeds maximum length of 100' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 100 } } },
            { name: 'version', labelKey: 'field_sops_version', enLabel: 'Version', zhLabel: '版本号', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 50) return { valid: false, error: 'Exceeds maximum length of 50' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 50 } } },
            { name: 'enabled', labelKey: 'field_sops_enabled', enLabel: 'Enabled', zhLabel: '是否启用', required: false, validation: { type: 'enum', enumValues: ['TRUE', 'FALSE'] } },
            { name: 'description', labelKey: 'field_sops_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'string', maxLength: 500 } },
            { name: 'triggerCondition', labelKey: 'field_sops_triggerCondition', enLabel: 'Trigger Condition', zhLabel: '触发条件', required: false, validation: { type: 'string', maxLength: 500 } },
            { name: 'targetSolution', labelKey: 'field_sops_targetSolution', enLabel: 'Target Solution', zhLabel: '目标解决方案', required: false, validation: { type: 'string', maxLength: 100 } },
            { name: 'stepsDescription', labelKey: 'field_sops_stepsDescription', enLabel: 'Steps Description', zhLabel: '诊断步骤', required: false, validation: { type: 'string', maxLength: 1000 } },
        ],
        sampleData: [
            {
                name: 'Server Restart',
                version: 'v1.0',
                enabled: 'TRUE',
                description: 'Regularly restart servers to free resources',
                triggerCondition: 'Memory usage over 90%',
                targetSolution: 'universal',
                stepsDescription: '1.Check current memory usage;2.Notify relevant personnel;3.Execute restart;4.Verify service recovery',
            },
            {
                name: 'Log Cleanup',
                version: 'v1.1',
                enabled: 'TRUE',
                description: 'Regularly clean up log files',
                triggerCondition: 'Disk usage over 80%',
                targetSolution: 'universal',
                stepsDescription: '1.Check log directory;2.Delete logs older than 7 days;3.Verify disk space',
            },
            {
                name: 'CRM Service Recovery',
                version: 'v2.0',
                enabled: 'TRUE',
                description: 'CRM solution service recovery',
                triggerCondition: 'HTTP 5xx errors > 5%',
                targetSolution: 'crm-commerce',
                stepsDescription: '1.Check CRM service status;2.Check database connectivity;3.Restart CRM service;4.Verify service recovery',
            },
        ],
    },
    Whitelist: {
        resourceType: 'Whitelist',
        sheetName: 'Whitelist',
        descriptionSheetName: '字段说明',
        fields: [
            { name: 'pattern', labelKey: 'field_whitelist_pattern', enLabel: 'Command', zhLabel: '命令', required: true, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (!/^[a-zA-Z0-9_\-./\s]+$/.test(value)) {
                    return { valid: false, error: 'Contains invalid characters' }
                }
                if (value.length > 500) {
                    return { valid: false, error: 'Exceeds maximum length of 500' }
                }
                return { valid: true }
            }, description: 'validationWhitelistPattern', descriptionParams: { max: 500 } } },
            { name: 'description', labelKey: 'field_whitelist_description', enLabel: 'Description', zhLabel: '描述', required: false, validation: { type: 'custom', customValidator: (value: string) => {
                if (!value.trim()) return { valid: true }
                if (hasXssChars(value)) return { valid: false, error: 'Contains invalid characters (< > " \' & ` /)' }
                if (value.length > 500) return { valid: false, error: 'Exceeds maximum length of 500' }
                return { valid: true }
            }, description: 'validationXssProtected', descriptionParams: { max: 500 } } },
            { name: 'enabled', labelKey: 'field_whitelist_enabled', enLabel: 'Enabled', zhLabel: '是否启用', required: false, validation: { type: 'enum', enumValues: ['TRUE', 'FALSE'] } },
        ],
        sampleData: [
            { pattern: 'ls -la', description: 'List files', enabled: 'TRUE' },
            { pattern: 'ps aux', description: 'View processes', enabled: 'TRUE' },
            { pattern: 'cat /var/log/syslog', description: 'View logs', enabled: 'FALSE' },
        ],
    },
}

export function getValidationRuleDescription(validation: FieldMetadata['validation'], t: (key: string, params?: Record<string, any>) => string): string {
    if (!validation) return ''
    const { type, maxLength, minLength, enumValues, pattern, separator, description, descriptionParams } = validation
    const rules: string[] = []

    if (type === 'string') {
        rules.push(t('importExport.validationString'))
        if (maxLength) rules.push(t('importExport.validationMaxLength', { max: maxLength }))
        if (minLength) rules.push(t('importExport.validationMinLength', { min: minLength }))
    } else if (type === 'number') {
        rules.push(t('importExport.validationNumber'))
    } else if (type === 'boolean') {
        rules.push(t('importExport.validationBoolean'))
    } else if (type === 'enum') {
        rules.push(`${t('importExport.validationEnum')}: ${enumValues?.join('/')}`)
    } else if (type === 'ip') {
        rules.push(t('importExport.validationIp'))
    } else if (type === 'regex') {
        rules.push(`${t('importExport.validationRegex')}: ${pattern}`)
    } else if (type === 'array') {
        rules.push(t('importExport.validationArray').replace('分隔符', separator || ';'))
    } else if (type === 'custom') {
        if (description) {
            rules.push(t(`importExport.${description}`, descriptionParams || {}))
        } else {
            rules.push(t('importExport.validationCustom'))
        }
    }

    return rules.join('，')
}

export function getRequiredLabel(required: boolean, t: (key: string) => string): string {
    return required ? t('importExport.requiredLabel') : t('importExport.optionalLabel')
}

export function getExcelColumn(index: number): string {
    let column = ''
    while (index >= 0) {
        column = String.fromCharCode(65 + (index % 26)) + column
        index = Math.floor(index / 26) - 1
    }
    return column
}
