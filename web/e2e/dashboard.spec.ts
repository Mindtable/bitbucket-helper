/// <reference lib="dom" />

import { expect, test, type Locator, type Page } from '@playwright/test'

type FixtureJourney =
  | 'healthy-refresh'
  | 'partial-refresh'
  | 'content-success'
  | 'content-unavailable'
  | 'newer-activity'
  | 'stale-acknowledgment'

interface BrowserObservations {
  consoleErrors: string[]
  pageErrors: string[]
  unexpectedRequests: string[]
}

const observations = new WeakMap<Page, BrowserObservations>()
const viteOrigin = 'http://127.0.0.1:5173'
const action501Body = 'Could we cap the retry window and add a metric for exhausted attempts?'
const action501NewerBody =
  'Please cap the retry window at 30 seconds and emit a metric for exhausted attempts.'

test.beforeEach(async ({ page }) => {
  const observed: BrowserObservations = {
    consoleErrors: [],
    pageErrors: [],
    unexpectedRequests: [],
  }
  observations.set(page, observed)
  page.on('console', (message) => {
    if (message.type() === 'error') observed.consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => observed.pageErrors.push(error.message))
  page.on('request', (request) => {
    const resourceType = request.resourceType()
    const requestUrl = request.url()
    const isViteRequest = requestUrl === viteOrigin || requestUrl.startsWith(`${viteOrigin}/`)
    const isEmbeddedResource = requestUrl.startsWith('data:') || requestUrl.startsWith('blob:')
    if (
      resourceType === 'fetch' ||
      resourceType === 'xhr' ||
      (!isViteRequest && !isEmbeddedResource)
    ) {
      observed.unexpectedRequests.push(`${resourceType} ${requestUrl}`)
    }
  })
})

test.afterEach(async ({ page }) => {
  const observed = observations.get(page)
  expect(observed?.consoleErrors ?? []).toEqual([])
  expect(observed?.pageErrors ?? []).toEqual([])
  expect(observed?.unexpectedRequests ?? []).toEqual([])
  await expect
    .poll(() =>
      page.evaluate(() => ({
        localStorage: Object.keys(localStorage),
        sessionStorage: Object.keys(sessionStorage),
      })),
    )
    .toEqual({ localStorage: [], sessionStorage: [] })
})

async function gotoJourney(page: Page, journey: FixtureJourney) {
  await page.goto(`/?fixtureJourney=${journey}`)
  await expect(page.getByRole('heading', { level: 1, name: 'Bitbucket Helper' })).toBeVisible()
  await expect(page.locator('[data-refresh-status]')).toHaveText('Sync idle')
}

function needsAttentionToggle(page: Page) {
  return page.locator('.needs-attention-toggle')
}

function action501(page: Page) {
  return page.locator('[data-action-item-id="action_501"]')
}

function action501Drawer(page: Page) {
  return page.getByRole('complementary', { name: 'Add retry budget' })
}

async function recordTextMutations(locator: Locator) {
  await locator.evaluate((element) => {
    const windowWithHistory = window as Window & { __fixtureTextHistory?: string[] }
    windowWithHistory.__fixtureTextHistory = [element.textContent ?? '']
    new MutationObserver(() => {
      windowWithHistory.__fixtureTextHistory?.push(element.textContent ?? '')
    }).observe(element, { childList: true, characterData: true, subtree: true })
  })
}

async function textMutationHistory(page: Page) {
  return page.evaluate(
    () => (window as Window & { __fixtureTextHistory?: string[] }).__fixtureTextHistory ?? [],
  )
}

async function expectInsideViewport(locator: Locator) {
  await locator.scrollIntoViewIfNeeded()
  await expect
    .poll(() =>
      locator.evaluate((element) => {
        const rect = element.getBoundingClientRect()
        return (
          rect.width > 0 &&
          rect.height > 0 &&
          rect.left >= 0 &&
          rect.right <= window.innerWidth &&
          rect.top >= 0 &&
          rect.bottom <= window.innerHeight
        )
      }),
    )
    .toBe(true)
}

const appearanceCases = [
  { colorScheme: 'light' as const, canvas: 'rgb(244, 247, 252)' },
  { colorScheme: 'dark' as const, canvas: 'rgb(16, 21, 30)' },
]

async function openResponsiveDrawer(
  page: Page,
  width: 1024 | 736 | 360,
  appearance: (typeof appearanceCases)[number],
) {
  await page.setViewportSize({ width, height: 900 })
  await page.emulateMedia({ colorScheme: appearance.colorScheme })
  await gotoJourney(page, 'healthy-refresh')
  await action501(page).click()

  const overflow = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth)
  expect(
    await page.locator('html').evaluate((element) => getComputedStyle(element).backgroundColor),
  ).toBe(appearance.canvas)

  await expectInsideViewport(page.getByRole('button', { name: 'Refresh dashboard' }))
  await expectInsideViewport(needsAttentionToggle(page))
  await expectInsideViewport(action501Drawer(page).getByRole('button', { name: 'Close' }))

  return page.evaluate(() => {
    const feed = document.querySelector('.dashboard-feed')?.getBoundingClientRect()
    const drawer = document.querySelector('.pull-request-drawer')?.getBoundingClientRect()
    if (!feed || !drawer) throw new Error('expected feed and drawer geometry')
    return {
      feed: { x: feed.x, y: feed.y, width: feed.width, height: feed.height },
      drawer: { x: drawer.x, y: drawer.y, width: drawer.width },
    }
  })
}

test('healthy refresh preserves the initial snapshot and settles on dash_19', async ({ page }) => {
  await gotoJourney(page, 'healthy-refresh')

  await expect(page.locator('.dashboard-revision code')).toHaveText('dash_18')
  const payments = page.getByRole('region', { name: 'Payments API' })
  await expect(payments).toContainText('#184')
  await expect(payments).toContainText('Add retry budget')

  await page.getByRole('button', { name: 'Refresh dashboard' }).click()

  await expect(page.locator('.dashboard-revision code')).toHaveText('dash_19')
  await expect(payments).toContainText('#184')
  await expect(payments).toContainText('Synchronization idle')
  await expect(payments).toContainText('Fresh · Just now')
  await expect(page.locator('[data-refresh-status]')).toHaveText('Sync idle')
})

test('partial refresh scopes stale data to Web Store and retains PR #92', async ({ page }) => {
  await gotoJourney(page, 'partial-refresh')
  const store = page.getByRole('region', { name: 'Web Store' })
  await expect(store).toContainText('Fresh · 4 minutes ago')
  await expect(store.locator('[data-pull-request-id="pr_92"]')).toContainText(
    'Harden CSRF validation',
  )
  await page.getByRole('button', { name: 'Refresh dashboard' }).click()

  await expect(page.locator('.dashboard-revision code')).toHaveText('dash_19')
  await expect(store).toContainText('Refresh completed with stale Web Store data.')
  await expect(store).toContainText('Stale · 18 minutes ago')
  await expect(store.locator('[data-pull-request-id="pr_92"]')).toContainText(
    'Harden CSRF validation',
  )
  await expect(store).toContainText('Build failed')
  await expect(store).toContainText('2 failed checks')
  await expect(store).toContainText('5 of 7 checks')
  await expect(page.locator('[data-refresh-status]')).toHaveText('Sync needs attention')
})

test('successful exact-version acknowledgment updates counts without expanding attention', async ({
  page,
}) => {
  await gotoJourney(page, 'content-success')
  await expect(page.getByText(action501Body, { exact: true })).toHaveCount(0)
  await expect(needsAttentionToggle(page)).toContainText('2 open')

  await action501(page).click()
  const drawer = action501Drawer(page)
  await expect(drawer.getByRole('button', { name: 'Close' })).toBeFocused()
  await expect(drawer).toContainText('Activity version av_42')
  await expect(drawer.getByText(action501Body, { exact: true })).toBeVisible()

  await needsAttentionToggle(page).click()
  await expect(needsAttentionToggle(page)).toHaveAttribute('aria-expanded', 'false')
  await drawer.getByRole('button', { name: 'Acknowledge av_42' }).click()

  await expect(drawer).toContainText('Activity acknowledged.')
  await expect(needsAttentionToggle(page)).toContainText('1 open')
  await expect(needsAttentionToggle(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(page.locator('#needs-attention-body')).toHaveCount(0)
})

test('unavailable exact content remains scoped to the drawer and retries the selection', async ({
  page,
}) => {
  await gotoJourney(page, 'content-unavailable')
  await action501(page).click()
  const drawer = action501Drawer(page)

  await expect(drawer).toContainText('Activity version av_42')
  await expect(drawer).toContainText('Activity content is temporarily unavailable.')
  await expect(drawer.getByRole('button', { name: /Acknowledge/ })).toHaveCount(0)
  await expect(page.getByText(action501Body, { exact: true })).toHaveCount(0)

  await recordTextMutations(drawer)
  await drawer.getByRole('button', { name: 'Try again' }).click()
  await expect(drawer).toContainText('Activity content is temporarily unavailable.')
  await expect(drawer).toContainText('Activity version av_42')
  expect((await textMutationHistory(page)).some((text) => text.includes('Loading activity'))).toBe(
    true,
  )
})

test('newer activity withholds stale content until refreshed av_43 metadata arrives', async ({
  page,
}) => {
  await gotoJourney(page, 'newer-activity')
  await action501(page).click()
  const drawer = action501Drawer(page)

  await expect(drawer).toContainText('Activity version av_42')
  await expect(drawer).toContainText('Newer activity is available. Current version av_43.')
  await expect(page.getByText(action501Body, { exact: true })).toHaveCount(0)
  await expect(page.getByText(action501NewerBody, { exact: true })).toHaveCount(0)

  await drawer.getByRole('button', { name: 'Refresh', exact: true }).click()

  await expect(page.locator('.dashboard-revision code')).toHaveText('dash_19')
  await expect(drawer).toContainText('Activity version av_43')
  await expect(drawer.getByText(action501NewerBody, { exact: true })).toBeVisible()
  await expect(page.getByText(action501Body, { exact: true })).toHaveCount(0)
})

test('stale acknowledgment waits for refreshed metadata without success or removal', async ({
  page,
}) => {
  await gotoJourney(page, 'stale-acknowledgment')
  await action501(page).click()
  const drawer = action501Drawer(page)
  await expect(drawer.getByText(action501Body, { exact: true })).toBeVisible()
  await expect(needsAttentionToggle(page)).toContainText('2 open')

  await recordTextMutations(drawer)
  await drawer.getByRole('button', { name: 'Acknowledge av_42' }).click()

  await expect(page.locator('.dashboard-revision code')).toHaveText('dash_19')
  await expect(drawer).toContainText('Activity version av_43')
  await expect(drawer).not.toContainText('Activity acknowledged.')
  await expect(needsAttentionToggle(page)).toContainText('2 open')
  expect(
    (await textMutationHistory(page)).some((text) => text.includes('Refreshing activity at av_43')),
  ).toBe(true)
})

test('shared hierarchy, safe links, disabled build, focus return, and content lifetime hold', async ({
  page,
}) => {
  await gotoJourney(page, 'healthy-refresh')
  const store = page.getByRole('region', { name: 'Web Store' })
  const pr92 = store.locator('[data-pull-request-id="pr_92"]')
  await expect(pr92).toContainText('Build failed')
  await expect(pr92).toContainText('2 failed checks')
  await expect(pr92).toContainText('5 of 7 checks')

  const externalLinks = page.locator('a[target="_blank"]')
  expect(await externalLinks.count()).toBeGreaterThan(0)
  for (const link of await externalLinks.all()) {
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  }

  const originalUrl = page.url()
  const unavailableBuild = pr92.locator('[data-view-build]')
  await unavailableBuild.focus()
  await expect(unavailableBuild).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(originalUrl)

  await needsAttentionToggle(page).click()
  await expect(needsAttentionToggle(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(page.locator('#needs-attention-body')).toHaveCount(0)
  await expect(action501(page)).toHaveCount(0)
  await needsAttentionToggle(page).click()

  const invoker = action501(page)
  await expect(page.getByText(action501Body, { exact: true })).toHaveCount(0)
  await invoker.click()
  const drawer = action501Drawer(page)
  await expect(drawer.getByRole('button', { name: 'Close' })).toBeFocused()
  await expect(drawer.getByText(action501Body, { exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(invoker).toBeFocused()
  await expect(page.getByText(action501Body, { exact: true })).toHaveCount(0)

  await invoker.click()
  await action501Drawer(page).getByRole('button', { name: 'Close' }).click()
  await expect(invoker).toBeFocused()
})

for (const appearance of appearanceCases) {
  test(`healthy drawer keeps wide geometry in ${appearance.colorScheme} appearance`, async ({
    page,
  }) => {
    const geometry = await openResponsiveDrawer(page, 1024, appearance)
    expect(geometry.drawer.x).toBeGreaterThan(geometry.feed.x + geometry.feed.width)
    expect(geometry.drawer.width).toBeLessThan(1024 / 2)
  })

  for (const width of [736, 360] as const) {
    test(`healthy drawer fits ${width}px in ${appearance.colorScheme} appearance`, async ({
      page,
    }) => {
      const geometry = await openResponsiveDrawer(page, width, appearance)
      expect(Math.abs(geometry.drawer.x - geometry.feed.x)).toBeLessThanOrEqual(1)
      expect(Math.abs(geometry.drawer.width - geometry.feed.width)).toBeLessThanOrEqual(1)
      expect(geometry.drawer.y).toBeGreaterThanOrEqual(geometry.feed.y + geometry.feed.height)
    })
  }
}
