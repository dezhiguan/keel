// The subset of markdown-it's API used by the message renderer.
declare module 'markdown-it' {
  interface Token {
    children: Token[] | null
    attrSet(name: string, value: string): void
  }

  interface Options {
    html?: boolean
    breaks?: boolean
    linkify?: boolean
  }

  interface Renderer {
    rules: Record<string, (tokens: Token[], index: number, options: Options, env: unknown, renderer: Renderer) => string>
    renderToken(tokens: Token[], index: number, options: Options): string
    renderInlineAsText(tokens: Token[], options: Options, env: unknown): string
  }

  export default class MarkdownIt {
    constructor(options?: Options)
    renderer: Renderer
    utils: { escapeHtml(text: string): string }
    render(text: string): string
  }
}
