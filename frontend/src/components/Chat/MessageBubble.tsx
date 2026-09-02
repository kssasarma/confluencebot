import ReactMarkdown from 'react-markdown'
import { cn } from '../../lib/cn'
import type { Message, Source } from '../../types'
import Spinner from '../ui/Spinner'

export default function MessageBubble({ message }: { message: Message }) {
  const isUser = message.role === 'user'

  return (
    <div className={cn('flex gap-3 py-3', isUser && 'flex-row-reverse')}>
      <div className={cn(
        'flex-shrink-0 w-7 h-7 rounded-full flex items-center justify-center text-xs font-semibold',
        isUser ? 'bg-primary text-white' : 'bg-muted text-muted-foreground',
      )}>
        {isUser ? 'U' : 'AI'}
      </div>
      <div className={cn('flex flex-col gap-1 max-w-[80%]', isUser && 'items-end')}>
        <div className={cn(
          'rounded-2xl px-4 py-2.5 text-sm leading-relaxed',
          isUser
            ? 'bg-primary text-white rounded-tr-sm'
            : 'bg-surface border border-border text-foreground rounded-tl-sm',
        )}>
          {message.streaming && !message.content ? (
            <Spinner size="sm" />
          ) : (
            <ReactMarkdown
              components={{
                p: ({ children }) => <p className="mb-2 last:mb-0">{children}</p>,
                code: ({ children, className }) => {
                  const isBlock = className?.startsWith('language-')
                  return isBlock
                    ? <pre className="bg-background rounded-lg p-3 overflow-x-auto my-2 text-xs"><code>{children}</code></pre>
                    : <code className={cn('bg-background rounded px-1 py-0.5 text-xs', isUser && 'bg-primary-foreground/20')}>{children}</code>
                },
                ul: ({ children }) => <ul className="list-disc list-inside mb-2 space-y-0.5">{children}</ul>,
                ol: ({ children }) => <ol className="list-decimal list-inside mb-2 space-y-0.5">{children}</ol>,
                a: ({ href, children }) => <a href={href} target="_blank" rel="noopener noreferrer" className="underline underline-offset-2">{children}</a>,
              }}
            >
              {message.content || ''}
            </ReactMarkdown>
          )}
          {message.streaming && message.content && (
            <span className="inline-block w-1 h-3.5 bg-current ml-0.5 animate-pulse rounded-sm" />
          )}
        </div>
        {!isUser && message.sources && message.sources.length > 0 && (
          <SourcesList sources={message.sources} />
        )}
      </div>
    </div>
  )
}

function SourcesList({ sources }: { sources: Source[] }) {
  return (
    <div className="flex flex-wrap gap-1.5 mt-1 px-1">
      {sources.map((src, i) => (
        <a
          key={i}
          href={src.url}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-xs text-muted-foreground border border-border rounded-full px-2.5 py-0.5 hover:bg-surface-hover hover:text-foreground transition-colors"
          title={src.title}
        >
          <span className="w-3.5 h-3.5 flex items-center justify-center rounded-full bg-muted text-[9px] font-semibold flex-shrink-0">
            {i + 1}
          </span>
          <span className="truncate max-w-[120px]">{src.title}</span>
        </a>
      ))}
    </div>
  )
}
