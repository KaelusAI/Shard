/*
 * This file is part of Shard - https://github.com/KaelusAI/Shard
 * Copyright (C) 2026 KaelusAI
 *
 * Shard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Shard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.shard.alert

import ac.shard.utils.Message

enum class AlertType(
  val permission: String,
  val enabledMessage: Message,
  val disabledMessage: Message,
) {
  REGULAR("shard.alerts", Message.ALERTS_ENABLED, Message.ALERTS_DISABLED),
  BRAND("shard.brand", Message.BRAND_ALERTS_ENABLED, Message.BRAND_ALERTS_DISABLED),
  SUSPICIOUS(
    "shard.suspicious.alerts",
    Message.SUSPICIOUS_ALERTS_ENABLED,
    Message.SUSPICIOUS_ALERTS_DISABLED,
  ),
  MITIGATION(
    "shard.mitigations.alerts",
    Message.MITIGATION_ALERTS_ENABLED,
    Message.MITIGATION_ALERTS_DISABLED,
  ),
  CUSTOM("shard.alerts.custom", Message.ALERTS_ENABLED, Message.ALERTS_DISABLED);

  val api: ac.shard.api.alert.AlertType
    get() =
      when (this) {
        REGULAR -> ac.shard.api.alert.AlertType.FLAG
        BRAND -> ac.shard.api.alert.AlertType.BRAND
        SUSPICIOUS -> ac.shard.api.alert.AlertType.SUSPICIOUS
        MITIGATION -> ac.shard.api.alert.AlertType.MITIGATION
        CUSTOM -> ac.shard.api.alert.AlertType.CUSTOM
      }

  companion object {
    fun of(api: ac.shard.api.alert.AlertType): AlertType = entries.first { it.api == api }
  }
}
