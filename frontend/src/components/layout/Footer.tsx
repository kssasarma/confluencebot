export default function Footer() {
  return (
    <footer className="flex-shrink-0 border-t border-border bg-surface py-1.5 px-4 text-center text-2xs text-muted-foreground">
      A side project by{' '}
      <a
        href="https://www.linkedin.com/in/surya-kodamanchili/"
        target="_blank"
        rel="noopener noreferrer"
        className="underline hover:text-foreground transition-colors"
      >
        Satya Kodamanchili
      </a>
      {' · '}
      <a
        href="https://github.com/kssasarma"
        target="_blank"
        rel="noopener noreferrer"
        className="underline hover:text-foreground transition-colors"
      >
        github.com/kssasarma
      </a>
    </footer>
  )
}
