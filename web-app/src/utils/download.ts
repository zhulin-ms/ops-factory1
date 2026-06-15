/**
 * Shared download utilities for blob responses.
 * Extracted from HistoryPage for reuse across modules.
 */

/**
 * Extract filename from Content-Disposition header, with fallback.
 */
export function getDownloadFilename(response: Response, fallback: string): string {
    const disposition = response.headers.get('Content-Disposition')
    if (disposition) {
        // Try RFC 5987 filename*= first
        const utf8Match = disposition.match(/filename\*=(?:UTF-8|utf-8)''([^;]+)/i)
        if (utf8Match?.[1]) {
            try {
                return decodeURIComponent(utf8Match[1])
            } catch {
                return utf8Match[1]
            }
        }
        // Fall back to filename=
        const asciiMatch = disposition.match(/filename="?([^";]+)"?/i)
        const name = asciiMatch?.[1]?.trim()
        if (name) {
            return name
        }
    }
    return fallback
}

/**
 * Download a blob response as a file using a temporary anchor element.
 */
export async function downloadBlobResponse(response: Response, fallbackName: string): Promise<void> {
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = getDownloadFilename(response, fallbackName)
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
