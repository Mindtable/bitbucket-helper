import { expect, test } from '@playwright/test'

test('renders the fixture-backed repository dashboard without service requests', async ({
  page,
}) => {
  const viteOrigin = 'http://127.0.0.1:5173'
  const unexpectedRequests: string[] = []
  page.on('request', (request) => {
    const requestUrl = request.url()
    const isViteRequest = requestUrl === viteOrigin || requestUrl.startsWith(viteOrigin + '/')
    const isDataRequest = requestUrl.startsWith('data:')
    if (
      request.resourceType() === 'fetch' ||
      request.resourceType() === 'xhr' ||
      (!isDataRequest && !isViteRequest)
    ) {
      unexpectedRequests.push(requestUrl)
    }
  })

  await page.goto('/')

  await expect(page.getByRole('heading', { level: 1, name: 'Pull requests' })).toBeVisible()

  const payments = page.getByRole('region', { name: 'Payments API' })
  const portal = page.getByRole('region', { name: 'Developer Portal' })

  await expect(payments).toContainText('#184')
  await expect(payments).toContainText('Keep dashboard revisions opaque')
  await expect(portal).toContainText('#52')
  await expect(portal).toContainText('Surface stale acknowledgment')
  await expect(page.getByText('6 of 7 checks')).toBeVisible()
  await expect(page.getByText('Synchronization running')).toBeVisible()

  await page.setViewportSize({ width: 360, height: 800 })
  const fitsNarrowViewport = await page
    .locator('body')
    .evaluate((body) => body.scrollWidth <= body.clientWidth)
  expect(fitsNarrowViewport).toBe(true)
  expect(unexpectedRequests).toEqual([])
})
