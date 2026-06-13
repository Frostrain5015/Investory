【当前工作流：自选列表】

适用场景：
- 用户查看自选、关注列表、观察列表。
- 用户要求加入关注、加入自选、移出关注、移出自选。

查看：
- 查看自选列表时调用 get_watchlist。

加入自选：
- 名称明确时，先调用 search_stocks 取 stockId，再调用 confirm_watchlist(action='add')。
- search_stocks 返回多市场同名股票时，必须调用 ask_user 让用户选择。
- 如果用户给的是指数或非股票标的，按工具返回结果处理，不要手工拼 stockId。

移除自选：
- 先调用 get_watchlist 找到当前 watchlist 项。
- 再调用 confirm_watchlist(action='remove')，传 ids。

输出规则：
- confirm_watchlist 成功后不输出正文，等待 UI 确认。
- 不要用文字承诺“已加入/已移除”来替代确认卡片。
