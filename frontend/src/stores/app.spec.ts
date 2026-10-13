import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { authApi } from '@/api/core'
import { useAppStore } from './app'

vi.mock('@/api/core', () => ({ authApi: { me: vi.fn(), login: vi.fn(), logout: vi.fn(), systemInfo: vi.fn() } }))
vi.mock('@/api/http', () => ({ ensureCsrf: vi.fn().mockResolvedValue(undefined) }))
const me = { id:1, username:'operator', displayName:'运营人员', roles:['OPERATOR'], permissions:['INSPECTION_READ'],
  sites:[{id:1,code:'A',name:'景区A',shortName:'A'},{id:2,code:'B',name:'景区B',shortName:'B'}] }
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.mocked(authApi.me).mockResolvedValue(me) })
describe('global scenic selection', () => {
  it('keeps global selection across session refresh without falling back to the first site', async () => {
    const app=useAppStore(); app.selectSite(0); await app.loadMe()
    expect(app.isGlobal).toBe(true); expect(app.currentSite).toBeNull(); expect(app.sites).toHaveLength(2)
    expect(localStorage.getItem('snow.currentSiteId')).toBe('0')
  })
  it('keeps global limited to the sites granted to the signed-in user', async () => {
    vi.mocked(authApi.me).mockResolvedValue({...me,sites:[me.sites[1]!]})
    const app=useAppStore(); app.selectSite(0); await app.loadMe()
    expect(app.sites.map(x=>x.id)).toEqual([2]); expect(app.has('INSPECTION_MANAGE')).toBe(false)
    app.selectSite(2); expect(app.currentSite?.id).toBe(2); expect(app.isGlobal).toBe(false)
  })
})
