import { useEffect, useState } from 'react'

/**
 * Whether the browser believes it has a network connection.
 *
 * "Believes" is the important word: `navigator.onLine` is false only when the machine has no
 * network interface at all, and is true on a captive-portal Wi-Fi that passes nothing. It is
 * therefore reliable as a negative — offline means offline — and worthless as a positive, which
 * is exactly how it is used here: to explain a failure that already happened, never to predict
 * that a request will succeed.
 */
export function useOnlineStatus(): boolean {
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' || navigator.onLine !== false)

  useEffect(() => {
    const goOnline = () => setOnline(true)
    const goOffline = () => setOnline(false)

    window.addEventListener('online', goOnline)
    window.addEventListener('offline', goOffline)
    return () => {
      window.removeEventListener('online', goOnline)
      window.removeEventListener('offline', goOffline)
    }
  }, [])

  return online
}
