export const TOKEN_KEY = 'cb_token'
export const getToken = () => localStorage.getItem(TOKEN_KEY) ?? ''
export const authHeader = () => ({ Authorization: `Bearer ${getToken()}` })
