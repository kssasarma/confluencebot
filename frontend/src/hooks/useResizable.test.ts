import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { useResizable } from './useResizable'

const OPTIONS = {
  storageKey: 'test_width',
  defaultWidth: 280,
  minWidth: 220,
  maxWidth: 480,
  step: 16,
}

function press(key: string) {
  return { key, preventDefault: () => {} } as React.KeyboardEvent<HTMLElement>
}

beforeEach(() => localStorage.clear())

describe('useResizable', () => {
  it('exposes the slider semantics a keyboard user needs', () => {
    const { result } = renderHook(() => useResizable(OPTIONS))

    expect(result.current.handleProps).toMatchObject({
      role: 'separator',
      tabIndex: 0,
      'aria-orientation': 'vertical',
      'aria-valuenow': 280,
      'aria-valuemin': 220,
      'aria-valuemax': 480,
    })
    expect(result.current.handleProps['aria-label']).toBeTruthy()
  })

  it('moves in steps with the arrow keys and reports the new value', () => {
    const { result } = renderHook(() => useResizable(OPTIONS))

    act(() => result.current.handleProps.onKeyDown(press('ArrowRight')))
    expect(result.current.width).toBe(296)
    expect(result.current.handleProps['aria-valuenow']).toBe(296)

    act(() => result.current.handleProps.onKeyDown(press('ArrowLeft')))
    expect(result.current.width).toBe(280)
  })

  it('clamps to the configured range', () => {
    const { result } = renderHook(() => useResizable(OPTIONS))

    act(() => result.current.handleProps.onKeyDown(press('Home')))
    expect(result.current.width).toBe(220)

    act(() => result.current.handleProps.onKeyDown(press('ArrowLeft')))
    expect(result.current.width).toBe(220)

    act(() => result.current.handleProps.onKeyDown(press('End')))
    expect(result.current.width).toBe(480)

    act(() => result.current.handleProps.onKeyDown(press('ArrowRight')))
    expect(result.current.width).toBe(480)
  })

  it('resets on double click', () => {
    const { result } = renderHook(() => useResizable(OPTIONS))

    act(() => result.current.handleProps.onKeyDown(press('End')))
    act(() => result.current.handleProps.onDoubleClick())

    expect(result.current.width).toBe(280)
  })

  it('remembers the width across mounts', () => {
    const first = renderHook(() => useResizable(OPTIONS))
    act(() => first.result.current.handleProps.onKeyDown(press('ArrowRight')))
    first.unmount()

    const second = renderHook(() => useResizable(OPTIONS))
    expect(second.result.current.width).toBe(296)
  })

  it('ignores a stored width that no longer fits the range', () => {
    localStorage.setItem(OPTIONS.storageKey, JSON.stringify(9000))

    const { result } = renderHook(() => useResizable(OPTIONS))
    expect(result.current.width).toBe(280)
  })
})
