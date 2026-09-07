import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import Footer from './Footer'

describe('Footer', () => {
  it('renders the attribution text', () => {
    render(<Footer />)
    expect(screen.getByText(/A side project by/i)).toBeInTheDocument()
    expect(screen.getByText(/Satya Kodamanchili/i)).toBeInTheDocument()
  })

  it('links to LinkedIn profile', () => {
    render(<Footer />)
    const link = screen.getByText(/Satya Kodamanchili/i).closest('a')
    expect(link).toHaveAttribute('href', 'https://www.linkedin.com/in/surya-kodamanchili/')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('links to GitHub profile', () => {
    render(<Footer />)
    const link = screen.getByText('github.com/kssasarma').closest('a')
    expect(link).toHaveAttribute('href', 'https://github.com/kssasarma')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('renders a footer element', () => {
    const { container } = render(<Footer />)
    expect(container.querySelector('footer')).toBeInTheDocument()
  })
})
