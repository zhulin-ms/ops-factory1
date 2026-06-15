import { describe, it, expect } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'

/**
 * Static analysis tests: verify that ALL pages properly handle
 * gateway disconnection instead of showing infinite loading,
 * and use a unified error banner style.
 */

const SRC_DIR = path.resolve(__dirname, '..')

function readSource(relativePath: string): string {
    return fs.readFileSync(path.join(SRC_DIR, relativePath), 'utf-8')
}

describe('History page — connection error handling', () => {
    it('reads connectionError from useGoosed()', () => {
        const src = readSource('app/modules/history/pages/HistoryPage.tsx')
        expect(src).toContain('error: connectionError')
        expect(src).toContain('useGoosed()')
    })

    it('delegates loading state to useHistorySessions and surfaces error', () => {
        const src = readSource('app/modules/history/pages/HistoryPage.tsx')
        expect(src).toContain('useHistorySessions({')
        expect(src).toContain('historySessions.isLoading')
        expect(src).toContain('historySessions.error')
    })

    it('displays connectionError in the error banner', () => {
        const src = readSource('app/modules/history/pages/HistoryPage.tsx')
        expect(src).toContain('connectionError')
        expect(src).toContain('conn-banner conn-banner-error')
    })
})

describe('Chat page — connection error handling', () => {
    it('reads goosedError from useGoosed()', () => {
        const src = readSource('app/modules/chat/pages/ChatPage.tsx')
        expect(src).toContain('error: goosedError')
    })

    it('shows error state when initializing and not connected', () => {
        const src = readSource('app/modules/chat/pages/ChatPage.tsx')
        expect(src).toContain('isInitializing && !isConnected && goosedError')
    })

    it('displays goosedError message in error state', () => {
        const src = readSource('app/modules/chat/pages/ChatPage.tsx')
        expect(src).toContain('{goosedError}')
    })

    it('provides a back-to-home button in connection error state', () => {
        const src = readSource('app/modules/chat/pages/ChatPage.tsx')
        expect(src).toContain("t('chat.backToHome')")
    })

    it('still shows loading spinner when connected but initializing', () => {
        const src = readSource('app/modules/chat/pages/ChatPage.tsx')
        const connectionErrorBlock = src.indexOf('isInitializing && !isConnected && goosedError')
        const loadingBlock = src.indexOf("t('chat.loadingSession')")
        expect(connectionErrorBlock).toBeLessThan(loadingBlock)
    })
})

describe('Files page — connection error handling', () => {
    it('reads connectionError from useGoosed()', () => {
        const src = readSource('app/modules/files/pages/FilesPage.tsx')
        expect(src).toContain('error: connectionError')
    })

    it('sets isLoading to false when not connected', () => {
        const src = readSource('app/modules/files/pages/FilesPage.tsx')
        expect(src).toContain('if (!isConnected || agents.length === 0)')
        expect(src).toContain('setIsLoading(false)')
    })

    it('displays connection error banner', () => {
        const src = readSource('app/modules/files/pages/FilesPage.tsx')
        expect(src).toContain('connectionError')
        expect(src).toContain('conn-banner conn-banner-error')
    })
})

describe('Inbox page — connection error handling', () => {
    it('imports useGoosed', () => {
        const src = readSource('app/modules/inbox/pages/InboxPage.tsx')
        expect(src).toContain("import { useGoosed } from '../../../platform/providers/GoosedContext'")
    })

    it('reads isConnected and connectionError from useGoosed()', () => {
        const src = readSource('app/modules/inbox/pages/InboxPage.tsx')
        expect(src).toContain('isConnected')
        expect(src).toContain('error: connectionError')
    })

    it('displays connection error banner when not connected', () => {
        const src = readSource('app/modules/inbox/pages/InboxPage.tsx')
        expect(src).toContain('!isConnected && connectionError')
        expect(src).toContain('conn-banner conn-banner-error')
    })
})

describe('Unified error banner CSS class — conn-banner', () => {
    const pagesToCheck = [
        { file: 'app/modules/home/pages/HomePage.tsx', name: 'Home' },
        { file: 'app/modules/history/pages/HistoryPage.tsx', name: 'History' },
        { file: 'app/modules/files/pages/FilesPage.tsx', name: 'Files' },
        { file: 'app/modules/inbox/pages/InboxPage.tsx', name: 'Inbox' },
        { file: 'app/modules/agents/pages/AgentsPage.tsx', name: 'Agents' },
        { file: 'app/platform/scheduler/SchedulesPanel.tsx', name: 'SchedulesPanel' },
    ]

    for (const { file, name } of pagesToCheck) {
        it(`${name} uses conn-banner class for error display`, () => {
            const src = readSource(file)
            expect(src).toContain('conn-banner conn-banner-error')
        })
    }

    it('App.css defines conn-banner base class', () => {
        const css = readSource('App.css')
        expect(css).toContain('.conn-banner')
        expect(css).toContain('.conn-banner-error')
        expect(css).toContain('.conn-banner-warning')
    })

    it('no page uses inline styles for error banners', () => {
        for (const { file } of pagesToCheck) {
            const src = readSource(file)
            // Should NOT have inline red background for error banners
            const inlineErrorBanner = src.match(
                /background:\s*['"]?rgba\(239,\s*68,\s*68/
            )
            expect(inlineErrorBanner).toBeNull()
        }
    })
})

describe('Error banner position — after page header, before content', () => {
    it('History: conn-banner appears before search-container', () => {
        const src = readSource('app/modules/history/pages/HistoryPage.tsx')
        const bannerPos = src.indexOf('conn-banner conn-banner-error')
        const searchPos = src.indexOf('<ListWorkbench')
        expect(bannerPos).toBeLessThan(searchPos)
    })

    it('Files: conn-banner appears before search-container', () => {
        const src = readSource('app/modules/files/pages/FilesPage.tsx')
        const bannerPos = src.indexOf('conn-banner conn-banner-error')
        const searchPos = src.indexOf('<ListWorkbench')
        expect(bannerPos).toBeLessThan(searchPos)
    })

    it('Inbox: conn-banner appears before inbox-toolbar', () => {
        const src = readSource('app/modules/inbox/pages/InboxPage.tsx')
        const bannerPos = src.indexOf('conn-banner conn-banner-error')
        const toolbarPos = src.indexOf('<ListWorkbench')
        expect(bannerPos).toBeLessThan(toolbarPos)
    })

})

describe('GoosedContext — timeout and error propagation', () => {
    it('uses AbortSignal.timeout for agent fetch', () => {
        const src = readSource('app/platform/providers/GoosedContext.tsx')
        expect(src).toContain('AbortSignal.timeout(30000)')
    })

    it('sets isConnected to false on error', () => {
        const src = readSource('app/platform/providers/GoosedContext.tsx')
        expect(src).toContain('setIsConnected(false)')
    })

    it('exposes error in context value', () => {
        const src = readSource('app/platform/providers/GoosedContext.tsx')
        expect(src).toMatch(/value=\{\{[^}]*error/)
    })
})
