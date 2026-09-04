/** Toggles `role` in `roles`, refusing to drop the last one — a user needs at least one. */
export function toggleRole<T>(roles: T[], role: T): T[] {
  const has = roles.includes(role)
  if (has && roles.length === 1) return roles
  return has ? roles.filter(r => r !== role) : [...roles, role]
}
