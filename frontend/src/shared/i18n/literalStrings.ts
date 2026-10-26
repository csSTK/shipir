import ts from 'typescript'

export interface LiteralString {
  line: number
  text: string
  where: 'text' | 'expression' | 'attribute' | 'message'
}

/** JSX attributes whose value is read by users (screen readers, tooltips, placeholders, props that become visible text). */
const USER_FACING_ATTRIBUTE =
  /^(aria-label|aria-description|aria-placeholder|aria-roledescription|title|placeholder|alt|label|description|help|hint|summary|subtitle|caption|heading|emptyMessage|emptyText|[a-zA-Z]+(Label|Title|Text|Hint|Description|Message|Placeholder|Heading))$/

const hasLetter = (s: string) => /\p{L}/u.test(s)

/** Technical example values (`nginx:1.27`, `172.30.0.0/16`, `.tar`) are not prose and stay untranslated. */
const isTechnicalToken = (s: string) => !/\s/.test(s) && /[\d:/._@]/.test(s)

function literalLeaves(expression: ts.Expression, out: ts.Expression[] = []): ts.Expression[] {
  if (ts.isParenthesizedExpression(expression)) return literalLeaves(expression.expression, out)
  if (ts.isConditionalExpression(expression)) {
    literalLeaves(expression.whenTrue, out)
    literalLeaves(expression.whenFalse, out)
  } else if (ts.isBinaryExpression(expression)) {
    const kind = expression.operatorToken.kind
    if (kind === ts.SyntaxKind.AmpersandAmpersandToken) literalLeaves(expression.right, out)
    else if (kind === ts.SyntaxKind.BarBarToken || kind === ts.SyntaxKind.QuestionQuestionToken) {
      literalLeaves(expression.left, out)
      literalLeaves(expression.right, out)
    }
  } else if (ts.isStringLiteral(expression) || ts.isNoSubstitutionTemplateLiteral(expression) || ts.isTemplateExpression(expression)) {
    out.push(expression)
  }
  return out
}

function staticText(literal: ts.Expression): string {
  if (ts.isTemplateExpression(literal)) return literal.head.text + literal.templateSpans.map((s) => s.literal.text).join('')
  return (literal as ts.StringLiteral).text
}

/**
 * Finds user-facing literal text in the JSX of a TSX source: element text, string literals inside JSX
 * expressions (also in conditional branches) and user-facing attributes (`aria-label`, `title`, `placeholder`, ...).
 */
export function findLiteralStrings(fileName: string, source: string): LiteralString[] {
  const sf = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const found: LiteralString[] = []
  const lineOf = (node: ts.Node) => sf.getLineAndCharacterOfPosition(node.getStart()).line + 1

  const report = (node: ts.Node, text: string, where: LiteralString['where']) => found.push({ line: lineOf(node), text: text.trim().slice(0, 80), where })

  const checkLeaves = (expression: ts.Expression, where: LiteralString['where']) => {
    for (const leaf of literalLeaves(expression)) {
      const text = staticText(leaf)
      if (hasLetter(text) && !(where === 'attribute' && isTechnicalToken(text))) report(leaf, text, where)
    }
  }

  const visit = (node: ts.Node) => {
    if (ts.isJsxText(node)) {
      const text = node.text.replace(/&[a-z]+;|&#\d+;/g, ' ')
      if (hasLetter(text)) report(node, node.text, 'text')
    } else if (ts.isJsxExpression(node) && node.expression && (ts.isJsxElement(node.parent) || ts.isJsxFragment(node.parent))) {
      checkLeaves(node.expression, 'expression')
    } else if (ts.isJsxAttribute(node) && node.initializer && USER_FACING_ATTRIBUTE.test(node.name.getText())) {
      if (ts.isStringLiteral(node.initializer)) {
        if (hasLetter(node.initializer.text) && !isTechnicalToken(node.initializer.text)) report(node, node.initializer.text, 'attribute')
      } else if (ts.isJsxExpression(node.initializer) && node.initializer.expression) {
        checkLeaves(node.initializer.expression, 'attribute')
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return found
}

/** Capitalised literals that are not user-facing text (HTTP header names, file names, error class names). */
const NOT_A_MESSAGE = new Set(['Accept', 'Authorization', 'Content-Disposition', 'Content-Type', 'Retry-After', 'Dockerfile', 'ApiError'])

/** Property names whose capitalised values are identifiers, not text. */
const IDENTIFIER_PROPERTY = new Set(['kind', 'type', 'id', 'key', 'name', 'value', 'method', 'role', 'queryKey', 'tone', 'variant', 'size', 'className'])

/**
 * Finds message-like string literals (start with a capital letter followed by a lowercase letter or a space)
 * outside of JSX in any TS/TSX source, e.g. error messages, column headers and labels in maps. Everything
 * translatable goes through `t()`/`i18n.t()`, so a literal here means a missed translation.
 */
export function findLiteralMessages(fileName: string, source: string): LiteralString[] {
  const sf = ts.createSourceFile(fileName, source, ts.ScriptTarget.Latest, true, fileName.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS)
  const found: LiteralString[] = []

  const visit = (node: ts.Node) => {
    const isLiteral = ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) || ts.isTemplateExpression(node)
    if (isLiteral) {
      const text = ts.isTemplateExpression(node) ? node.head.text : node.text
      const parent = node.parent
      const isTranslationCall = ts.isCallExpression(parent) && /^(t|tr|i18n\.t)$/.test(parent.expression.getText())
      const isComparison = ts.isBinaryExpression(parent) && /^[=!]==?$/.test(parent.operatorToken.getText())
      const isPropertyName = ts.isPropertyAssignment(parent) && (parent.name === node || IDENTIFIER_PROPERTY.has(parent.name.getText()))
      const excluded =
        isTranslationCall ||
        isComparison ||
        isPropertyName ||
        ts.isImportDeclaration(parent) ||
        ts.isExportDeclaration(parent) ||
        ts.isCaseClause(parent) ||
        ts.isLiteralTypeNode(parent) ||
        ts.isElementAccessExpression(parent) ||
        ts.isPropertySignature(parent) ||
        ts.isEnumMember(parent) ||
        ts.isJsxAttribute(parent) ||
        NOT_A_MESSAGE.has(text)
      if (!excluded && /^[A-Z][a-z ]/.test(text.trim())) {
        found.push({ line: sf.getLineAndCharacterOfPosition(node.getStart()).line + 1, text: text.trim().slice(0, 80), where: 'message' })
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(sf)
  return found
}
