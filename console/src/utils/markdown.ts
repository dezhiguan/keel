import MarkdownIt from 'markdown-it'

// Only parser-generated markup reaches v-html. Model-provided HTML stays text.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: true })

markdown.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return renderer.renderToken(tokens, index, options)
}

// Do not fetch arbitrary remote images embedded in a model response.
markdown.renderer.rules.image = (tokens, index, options, env, renderer) =>
  markdown.utils.escapeHtml(renderer.renderInlineAsText(tokens[index].children ?? [], options, env))

markdown.renderer.rules.table_open = () => '<div class="markdown-table" tabindex="0" role="region" aria-label="表格"><table>\n'
markdown.renderer.rules.table_close = () => '</table></div>\n'

export function renderMessageMarkdown(text: string): string {
  return markdown.render(text)
}
