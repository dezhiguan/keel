import { describe, expect, it } from 'vitest'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import MessageMarkdown from '@/components/MessageMarkdown.vue'
import { renderMessageMarkdown } from './markdown'

describe('debug message Markdown', () => {
  it('renders parsed markup through the actual Vue component', async () => {
    const html = await renderToString(createSSRApp(MessageMarkdown, {
      text: '2. **查 Keel 规范**：检索已授权文档。\n\n<script>alert(1)</script>',
    }))
    expect(html).toContain('class="message-markdown"')
    expect(html).toContain('<ol start="2">')
    expect(html).toContain('<strong>查 Keel 规范</strong>')
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;')
    expect(html).not.toContain('<script>')
  })

  it('formats the capability list from the reported response', () => {
    const html = renderMessageMarkdown('2. **查 Keel 规范**：检索已授权文档，并附来源说明。\n3. **查 staging 数字员工**：查看登记列表。\n4. **生成文字分析报告**：整理结论、风险、证据与建议。\n\n暂不支持查询 prod。')
    expect(html).toContain('<ol start="2">')
    expect(html.match(/<li>/g)).toHaveLength(3)
    expect(html).toContain('<strong>查 Keel 规范</strong>')
    expect(html).toContain('<p>暂不支持查询 prod。</p>')
    expect(html).not.toContain('**')
  })

  it('supports headings, nested lists, quotes, inline code, and line breaks', () => {
    const html = renderMessageMarkdown('## 分析\n\n- 结论\n  - 子项\n\n> 引用\n\n读取 `agent.yaml`\n下一行')
    expect(html).toContain('<h2>分析</h2>')
    expect(html.match(/<ul>/g)).toHaveLength(2)
    expect(html).toContain('<blockquote>')
    expect(html).toContain('<code>agent.yaml</code>')
    expect(html).toContain('<br>\n下一行')
  })

  it('keeps partial streaming code fences readable and escapes their contents', () => {
    const partial = '```html\n<script>alert(1)</script>'
    const html = renderMessageMarkdown(partial)
    expect(html).toContain('<pre><code class="language-html">')
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;')
    expect(html).toContain('</code></pre>')
    expect(renderMessageMarkdown(`${partial}\n\`\`\``)).toContain('&lt;script&gt;alert(1)&lt;/script&gt;\n</code></pre>')
    expect(renderMessageMarkdown('正在 **分析')).toContain('正在 **分析')
    expect(renderMessageMarkdown('正在 **分析**')).toContain('<strong>分析</strong>')
  })

  it('wraps tables in a focusable scrolling region', () => {
    const html = renderMessageMarkdown('| 名称 | 环境 |\n| --- | --- |\n| 助理 | staging |')
    expect(html).toContain('class="markdown-table" tabindex="0" role="region" aria-label="表格"')
    expect(html).toContain('<th>名称</th>')
    expect(html).toContain('<td>staging</td>')
    expect(html).toContain('</table></div>')
  })

  it('opens source links safely and auto-links plain URLs', () => {
    const html = renderMessageMarkdown('[来源](https://example.com/docs?q=1&b=2)\nhttps://example.com/help')
    expect(html).toContain('href="https://example.com/docs?q=1&amp;b=2"')
    expect(html.match(/target="_blank" rel="noopener noreferrer"/g)).toHaveLength(2)
  })

  it('escapes raw HTML and never renders model-controlled elements', () => {
    const html = renderMessageMarkdown('<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n<svg onload=alert(1)>\n<iframe src="https://example.com"></iframe>')
    expect(html).not.toMatch(/<(script|img|svg|iframe)\b/)
    expect(html).toContain('&lt;script&gt;')
  })

  it.each(['javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'vbscript:alert(1)', 'data:text/html;base64,PHNjcmlwdD4=', 'javascript&#x3a;alert(1)'])('rejects unsafe link %s', (url) => {
    expect(renderMessageMarkdown(`[打开](${url})`)).not.toContain('<a ')
  })

  it('shows image descriptions without loading remote images or HTML in alt text', () => {
    const html = renderMessageMarkdown('![图示 <img src=x onerror=alert(1)>](https://example.com/tracker.png)')
    expect(html).toContain('图示')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('https://example.com/tracker.png')
  })

  it('handles empty and plain text replies', () => {
    expect(renderMessageMarkdown('')).toBe('')
    expect(renderMessageMarkdown('你好，已完成。')).toBe('<p>你好，已完成。</p>\n')
  })
})
