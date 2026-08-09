# 麦当劳点餐技能

当用户需要在麦当劳下单、查菜单、查活动、用优惠券时，使用以下工具：

## 可用工具

- `mcdonaldOrder` — 一站式下单。传地址+食物名即可，自动查门店选菜单
- `mcdonaldCampaigns` — 查当前活动
- `mcdonaldCoupons` — 查可用优惠券
- `mcdonaldOrderStatus` — 查订单状态

## 点餐流程

1. 用户说想吃什么 → 直接调 `mcdonaldOrder`，传入地址和食物描述
2. 用户只想看看有什么 → 调 `mcdonaldCampaigns` 查活动
3. 用户问优惠 → 调 `mcdonaldCoupons`
4. 用户问订单到哪了 → 调 `mcdonaldOrderStatus`

## 注意事项

- `mcdonaldOrder` 只需要地址和想吃的东西，其他的工具会自动处理
- 不要直接调用任何 `mcp_*` 开头的底层工具，始终用 `mcdonald*` 系列
- 如果用户没给地址，询问配送地址
