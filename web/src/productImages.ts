// 商品图映射：图片已本地化到 web/public/products/，由前端自身提供。
//
// 为什么不用外链：外链在受限浏览器（含内置预览）里加载不到，还依赖外部网络。
// 本地化后离线可用，且不再受第三方防盗链和限流影响。
// 图片来源见 web/public/products/CREDITS.txt（Wikimedia Commons，含 Unsplash 授权作品）。
// 数据库目前没有图片字段，因此按 SKU 建立前端映射；后端新增字段后可整体替换本模块。

export type ProductVisual = {
  src: string
  tone: string
}

const LAPTOP = [
  '/products/laptop-1.jpg',
  '/products/laptop-2.jpg',
  '/products/laptop-3.jpg',
  '/products/laptop-4.jpg',
  '/products/laptop-5.jpg',
  '/products/laptop-6.jpg',
  '/products/laptop-7.jpg',
  '/products/laptop-8.jpg',
  '/products/laptop-9.jpg',
  '/products/laptop-10.jpg',
]

const PHONE = [
  '/products/phone-1.jpg',
  '/products/phone-2.jpg',
  '/products/phone-3.jpg',
  '/products/phone-4.jpg',
]

const HEADPHONE = [
  '/products/headphone-1.jpg',
  '/products/headphone-2.jpg',
  '/products/headphone-3.jpg',
  '/products/headphone-4.jpg',
]

const MONITOR = [
  '/products/monitor-1.jpg',
  '/products/monitor-2.jpg',
  '/products/monitor-3.jpg',
]

const TABLET = [
  '/products/tablet-1.jpg',
  '/products/tablet-2.jpg',
  '/products/tablet-3.jpg',
]

const KEYBOARD = [
  '/products/keyboard-1.jpg',
  '/products/keyboard-2.jpg',
  '/products/keyboard-3.jpg',
]

const MOUSE = [
  '/products/mouse-1.jpg',
  '/products/mouse-2.jpg',
  '/products/mouse-3.jpg',
]

// 逐 SKU 指定，保证同一页里同品类商品不出现重复图片。
const SKU_IMAGES: Record<string, string> = {
  'sku-air-16': LAPTOP[0],
  'sku-air-14': LAPTOP[1],
  'sku-air-ultra': LAPTOP[2],
  'sku-pro-create': LAPTOP[3],
  'sku-leaf-13': LAPTOP[4],
  'sku-leaf-ultra': LAPTOP[5],
  'sku-book-15': LAPTOP[6],
  'sku-pro-16': LAPTOP[7],
  'sku-forge-16': LAPTOP[8],
  'sku-max-32': LAPTOP[9],
  'sku-hush-air': HEADPHONE[0],
  'sku-buds-2': HEADPHONE[1],
  'sku-hush-studio': HEADPHONE[3],
  'sku-lumen-12': PHONE[0],
  'sku-lumen-12p': PHONE[1],
  'sku-pulse-5': PHONE[2],
  'sku-pixel-8': PHONE[3],
  'sku-vista-27': MONITOR[0],
  'sku-vista-32': MONITOR[1],
  'sku-tab-11': TABLET[0],
  'sku-tab-12p': TABLET[1],
  'sku-click-kbd': KEYBOARD[0],
  'sku-click-mouse': MOUSE[0],
}

const CATEGORY_POOL: Record<string, string[]> = {
  laptop: LAPTOP,
  phone: PHONE,
  headphone: HEADPHONE,
  monitor: MONITOR,
  tablet: TABLET,
  keyboard: KEYBOARD,
  mouse: MOUSE,
}

export const CATEGORY_LABELS: Record<string, string> = {
  laptop: '笔记本',
  phone: '手机',
  headphone: '耳机',
  monitor: '显示器',
  tablet: '平板',
  keyboard: '键盘',
  mouse: '鼠标',
}

// 新增 SKU 未登记图片时，按 SKU 哈希稳定落到同品类图片池，避免每次渲染跳动。
function hash(value: string): number {
  let sum = 0
  for (let index = 0; index < value.length; index += 1) {
    sum = (sum * 31 + value.charCodeAt(index)) >>> 0
  }
  return sum
}

export function visualFor(skuId: string, category?: string): ProductVisual {
  const tone = category && CATEGORY_LABELS[category] ? `tone-${category}` : 'tone-generic'
  const known = SKU_IMAGES[skuId]
  if (known) return { src: known, tone }
  const pool = category ? CATEGORY_POOL[category] : undefined
  if (pool && pool.length > 0) return { src: pool[hash(skuId) % pool.length], tone }
  return { src: '', tone: 'tone-generic' }
}

export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category] ?? category
}

// 图片来自 Wikimedia Commons，按来源标注许可归属。
export const IMAGE_SOURCE_NOTE =
  '商品图片取自 Wikimedia Commons（含 Unsplash 授权作品），已本地化到 web/public/products，仅用于演示。'
