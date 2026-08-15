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

function expectedExternalLinks(page: Page) {
  const payments = page.getByRole('region', { name: 'Payments API' })
  const store = page.getByRole('region', { name: 'Web Store' })
  const drawer = action501Drawer(page)
  return [
    {
      label: 'Payments repository',
      locator: payments.getByRole('link', {
        name: 'Open Payments API repository in a new tab',
      }),
      href: 'https://bitbucket.org/mindtable/payments-api',
    },
    {
      label: 'Payments PR #184',
      locator: payments
        .locator('[data-pull-request-id="pr_184"]')
        .getByRole('link', { name: 'Open in Bitbucket' }),
      href: 'https://bitbucket.org/mindtable/payments-api/pull-requests/184',
    },
    {
      label: 'Payments PR #179',
      locator: payments
        .locator('[data-pull-request-id="pr_179"]')
        .getByRole('link', { name: 'Open in Bitbucket' }),
      href: 'https://bitbucket.org/mindtable/payments-api/pull-requests/179',
    },
    {
      label: 'Web Store repository',
      locator: store.getByRole('link', {
        name: 'Open Web Store repository in a new tab',
      }),
      href: 'https://bitbucket.org/mindtable/web-store',
    },
    {
      label: 'Web Store PR #92',
      locator: store
        .locator('[data-pull-request-id="pr_92"]')
        .getByRole('link', { name: 'Open in Bitbucket' }),
      href: 'https://bitbucket.org/mindtable/web-store/pull-requests/92',
    },
    {
      label: 'Drawer PR #184',
      locator: drawer.getByRole('link', { name: 'Open in Bitbucket' }),
      href: 'https://bitbucket.org/mindtable/payments-api/pull-requests/184',
    },
    {
      label: 'Drawer activity 501',
      locator: drawer.getByRole('link', { name: 'Open activity in Bitbucket' }),
      href: 'https://bitbucket.org/mindtable/payments-api/pull-requests/184#comment-501',
    },
  ]
}

async function expectSafeExternalLinks(page: Page) {
  const expectedLinks = expectedExternalLinks(page)
  await expect(page.getByRole('link')).toHaveCount(expectedLinks.length)
  for (const expectedLink of expectedLinks) {
    await expect(expectedLink.locator, expectedLink.label).toHaveCount(1)
    await expect(expectedLink.locator, expectedLink.label).toHaveAttribute(
      'href',
      expectedLink.href,
    )
    await expect(expectedLink.locator, expectedLink.label).toHaveAttribute('target', '_blank')
    const relTokens = (await expectedLink.locator.getAttribute('rel'))?.split(/\s+/).sort()
    expect(relTokens, expectedLink.label).toEqual(['noopener', 'noreferrer'])
  }
}

function expectedOpenDashboardControls(page: Page) {
  const payments = page.getByRole('region', { name: 'Payments API' })
  const store = page.getByRole('region', { name: 'Web Store' })
  const pr184 = payments.locator('[data-pull-request-id="pr_184"]')
  const pr179 = payments.locator('[data-pull-request-id="pr_179"]')
  const pr92 = store.locator('[data-pull-request-id="pr_92"]')
  const drawer = action501Drawer(page)
  const externalLinks = expectedExternalLinks(page)
  return [
    {
      label: 'Refresh dashboard',
      locator: page.getByRole('button', { name: 'Refresh dashboard' }),
    },
    { label: 'Needs attention disclosure', locator: needsAttentionToggle(page) },
    { label: 'Action 501', locator: action501(page) },
    { label: 'Action 502', locator: page.locator('[data-action-item-id="action_502"]') },
    { label: 'Payments repository link', locator: externalLinks[0]!.locator },
    { label: 'PR #184 review', locator: pr184.getByRole('button', { name: 'Review context' }) },
    { label: 'PR #184 link', locator: externalLinks[1]!.locator },
    { label: 'PR #179 review', locator: pr179.getByRole('button', { name: 'Review context' }) },
    { label: 'PR #179 link', locator: externalLinks[2]!.locator },
    { label: 'Web Store repository link', locator: externalLinks[3]!.locator },
    { label: 'PR #92 unavailable build', locator: pr92.locator('[data-view-build]') },
    { label: 'PR #92 review', locator: pr92.getByRole('button', { name: 'Review context' }) },
    { label: 'PR #92 link', locator: externalLinks[4]!.locator },
    { label: 'Drawer close', locator: drawer.getByRole('button', { name: 'Close' }) },
    { label: 'Drawer PR link', locator: externalLinks[5]!.locator },
    { label: 'Drawer activity link', locator: externalLinks[6]!.locator },
    {
      label: 'Drawer acknowledgment',
      locator: drawer.getByRole('button', { name: 'Acknowledge av_42' }),
    },
  ]
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
  await expect(
    action501Drawer(page).getByRole('button', { name: 'Acknowledge av_42' }),
  ).toBeVisible()
  const overflow = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth)
  expect(
    await page.locator('html').evaluate((element) => getComputedStyle(element).backgroundColor),
  ).toBe(appearance.canvas)

  const geometry = await page.evaluate(() => {
    const layout = document.querySelector('.dashboard-layout')?.getBoundingClientRect()
    const feed = document.querySelector('.dashboard-feed')?.getBoundingClientRect()
    const drawer = document.querySelector('.pull-request-drawer')?.getBoundingClientRect()
    if (!layout || !feed || !drawer) throw new Error('expected layout, feed, and drawer geometry')
    const scrollX = window.scrollX
    const scrollY = window.scrollY
    return {
      layout: {
        left: layout.left + scrollX,
        right: layout.right + scrollX,
        top: layout.top + scrollY,
      },
      feed: {
        left: feed.left + scrollX,
        right: feed.right + scrollX,
        top: feed.top + scrollY,
        bottom: feed.bottom + scrollY,
        width: feed.width,
        height: feed.height,
      },
      drawer: {
        left: drawer.left + scrollX,
        right: drawer.right + scrollX,
        top: drawer.top + scrollY,
        bottom: drawer.bottom + scrollY,
        width: drawer.width,
        height: drawer.height,
      },
    }
  })

  const expectedControls = expectedOpenDashboardControls(page)
  await expect(page.locator('button:visible, a:visible')).toHaveCount(expectedControls.length)
  for (const expectedControl of expectedControls) {
    await expect(expectedControl.locator, expectedControl.label).toHaveCount(1)
    await expect(expectedControl.locator, expectedControl.label).toBeVisible()
    await expectInsideViewport(expectedControl.locator)
  }

  return geometry
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

test('Escape restores Needs attention after successful acknowledgment removes its invoker', async ({
  page,
}) => {
  await gotoJourney(page, 'content-success')
  await action501(page).click()
  const drawer = action501Drawer(page)
  await expect(drawer.getByRole('button', { name: 'Close' })).toBeFocused()

  await drawer.getByRole('button', { name: 'Acknowledge av_42' }).click()
  await expect(action501(page)).toHaveCount(0)
  await expect(needsAttentionToggle(page)).toContainText('1 open')
  await page.keyboard.press('Escape')

  await expect(drawer).toHaveCount(0)
  await expect(needsAttentionToggle(page)).toBeFocused()
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
  await expectSafeExternalLinks(page)
  await page.keyboard.press('Escape')
  await expect(drawer).toHaveCount(0)
  await expect(invoker).toBeFocused()
  await expect(page.getByText(action501Body, { exact: true })).toHaveCount(0)

  await invoker.click()
  await action501Drawer(page).getByRole('button', { name: 'Close' }).click()
  await expect(invoker).toBeFocused()

  await invoker.click()
  await expect(action501Drawer(page).getByRole('button', { name: 'Close' })).toBeFocused()
  await needsAttentionToggle(page).click()
  await expect(action501(page)).toHaveCount(0)
  await action501Drawer(page).getByRole('button', { name: 'Close' }).click()
  await expect(needsAttentionToggle(page)).toBeFocused()
})

for (const appearance of appearanceCases) {
  test(`healthy drawer keeps wide geometry in ${appearance.colorScheme} appearance`, async ({
    page,
  }) => {
    const geometry = await openResponsiveDrawer(page, 1024, appearance)
    const expectedDrawerWidth = Math.min(448, Math.max(320, 1024 * 0.32))
    expect(Math.abs(geometry.drawer.width - expectedDrawerWidth)).toBeLessThanOrEqual(2)
    expect(Math.abs(geometry.drawer.top - geometry.feed.top)).toBeLessThanOrEqual(1)
    expect(Math.abs(geometry.drawer.left - geometry.feed.right - 16)).toBeLessThanOrEqual(1)
    expect(Math.abs(geometry.feed.left - geometry.layout.left)).toBeLessThanOrEqual(1)
    expect(Math.abs(geometry.drawer.right - geometry.layout.right)).toBeLessThanOrEqual(1)
    expect(geometry.drawer.height).toBeGreaterThan(320)
    expect(geometry.drawer.bottom).toBeGreaterThan(geometry.drawer.top)
  })

  for (const width of [736, 360] as const) {
    test(`healthy drawer fits ${width}px in ${appearance.colorScheme} appearance`, async ({
      page,
    }) => {
      const geometry = await openResponsiveDrawer(page, width, appearance)
      expect(Math.abs(geometry.drawer.left - geometry.feed.left)).toBeLessThanOrEqual(1)
      expect(Math.abs(geometry.drawer.width - geometry.feed.width)).toBeLessThanOrEqual(1)
      expect(Math.abs(geometry.drawer.top - geometry.feed.bottom - 16)).toBeLessThanOrEqual(1)
      expect(Math.abs(geometry.drawer.left - geometry.layout.left)).toBeLessThanOrEqual(1)
      expect(Math.abs(geometry.drawer.right - geometry.layout.right)).toBeLessThanOrEqual(1)
      expect(geometry.drawer.height).toBeGreaterThan(320)
      expect(geometry.drawer.bottom).toBeGreaterThan(geometry.feed.bottom)
    })
  }
}
