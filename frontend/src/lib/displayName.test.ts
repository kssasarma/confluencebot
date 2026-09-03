import { describe, expect, it } from 'vitest'
import { displayNameFromEmail } from './displayName'

/**
 * The greeting on an empty conversation is the only place a name appears, and the account record
 * has none — so everything here is derived from the address, and the derivation has to fail
 * quietly rather than greet somebody by a string that is obviously not their name.
 */
describe('a name derived from an address', () => {
  it('splits the local part on the separators addresses use', () => {
    expect(displayNameFromEmail('priya.sharma@acme.com')).toBe('Priya Sharma')
    expect(displayNameFromEmail('arjun_menon@acme.com')).toBe('Arjun Menon')
    expect(displayNameFromEmail('ravi-kumar@acme.com')).toBe('Ravi Kumar')
    expect(displayNameFromEmail('k.s.sasarma@acme.com')).toBe('K S Sasarma')
  })

  it('leaves a single-token address as one capitalised word', () => {
    expect(displayNameFromEmail('kssasarma@gmail.com')).toBe('Kssasarma')
  })

  it('drops the plus-addressing tag, which is routing and not a name', () => {
    expect(displayNameFromEmail('priya+confluence@acme.com')).toBe('Priya')
  })

  it('drops parts that are only digits', () => {
    expect(displayNameFromEmail('priya.sharma.1985@acme.com')).toBe('Priya Sharma')
  })

  it('capitalises without flattening a name that capitalises itself', () => {
    expect(displayNameFromEmail('mcCarthy.o.Brien@acme.com')).toBe('McCarthy O Brien')
  })

  it('calms an address shouted in capitals, but leaves plausible initials alone', () => {
    expect(displayNameFromEmail('KSSASARMA@acme.com')).toBe('Kssasarma')
    expect(displayNameFromEmail('KS.sharma@acme.com')).toBe('KS Sharma')
  })

  it('yields nothing rather than a name nobody has', () => {
    expect(displayNameFromEmail('12345@acme.com')).toBe('')
    expect(displayNameFromEmail('')).toBe('')
    expect(displayNameFromEmail(null)).toBe('')
    expect(displayNameFromEmail(undefined)).toBe('')
  })

  it('clips a local part long enough to break the greeting across lines', () => {
    expect(displayNameFromEmail('averyveryverylongaddressindeed.sharma@acme.com'))
      .toBe('Averyveryverylongaddress…')
  })
})
