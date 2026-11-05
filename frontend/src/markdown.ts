import MarkdownIt from 'markdown-it'
import katex from 'katex'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
})

/**
 * 公式渲染：先把 $...$ / $$...$$ 换成占位符，交给 markdown 渲染，再换回 KaTeX HTML。
 *
 * ⚠️ 占位符必须用 Unicode 私有区字符（U+E000/U+E001），不能用 \u0000：
 * CommonMark 规范要求把 NUL 替换成 U+FFFD（�），markdown-it 忠实执行了这条，
 * 占位符会被破坏，最终以「◆MATH0◆」的乱码形式泄漏进正文（2026-11-05 实锤）。
 * 私有区字符不会被任何 markdown 规则碰，正常用户内容也几乎不可能出现。
 */
const PH_OPEN = '\uE000'
const PH_CLOSE = '\uE001'
const PH_RE = /\uE000MATH(\d+)\uE001/g

/**
 * 流式光标：直接注入到最后一个「文本容器」的闭合标签前，
 * 让光标紧跟最后一行文字。不能用 `.content::after` —— 块级容器上挂
 * 伪元素会掉到下一行左对齐（2026-11-05 截图实锤），CSS 也够不到
 * 「blockquote > p 的行尾」这种深层位置。
 */
const CARET = '<span class="caret"></span>'
// p/li/标题/表格单元/代码 是真正「贴着文字」的容器；取最后一个匹配
const LAST_TEXT_CLOSE = /<\/(p|li|h[1-6]|td|th|dt|dd|code)>(?![\s\S]*<\/(?:p|li|h[1-6]|td|th|dt|dd|code)>)/

export function renderMarkdown(source: string): string {
  const maths: string[] = []
  const stash = (tex: string, display: boolean) => {
    const html = katex.renderToString(tex, {
      displayMode: display,
      throwOnError: false,
      output: 'html'
    })
    maths.push(html)
    return `${PH_OPEN}MATH${maths.length - 1}${PH_CLOSE}`
  }

  const withBlocks = source.replace(/\$\$([\s\S]+?)\$\$/g, (_, tex: string) => stash(tex, true))
  const withInline = withBlocks.replace(/(^|[^\\$])\$([^\n$]+?)\$/g, (_, lead: string, tex: string) => `${lead}${stash(tex, false)}`)

  const rendered = md.render(withInline)
  return rendered.replace(PH_RE, (_, idx: string) => maths[Number(idx)] ?? '')
}

/** 流式过程中内容不完整，做纯文本兜底渲染，并在文字末尾挂上光标 */
export function renderStreaming(source: string): string {
  const html = md.render(source)
  const m = html.match(LAST_TEXT_CLOSE)
  return m && m.index !== undefined ? html.slice(0, m.index) + CARET + html.slice(m.index) : html + CARET
}
