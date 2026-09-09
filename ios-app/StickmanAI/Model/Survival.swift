// Estado de supervivencia por personaje (vida/hambre/sed), espejo de src/memory/survival.js
// y de los drenajes de jsCharacterEngine.js (HUNGER_PER_POLL=0.5, THIRST_PER_POLL=0.75,
// HP_LOSS_PER_POLL_STARVED=1.5). Valores en [0,100].

import Foundation

struct Survival: Codable, Equatable {
  var hp: Double = 100
  var hunger: Double = 100
  var thirst: Double = 100

  var isDead: Bool { hp <= 0 }

  static let pollInterval: TimeInterval = 10.0     // SURVIVAL_POLL_MS
  static let hungerPerPoll: Double = 0.5
  static let thirstPerPoll: Double = 0.75
  static let hpLossPerPollStarved: Double = 1.5

  mutating func tick() {
    hunger = max(0, hunger - Survival.hungerPerPoll)
    thirst = max(0, thirst - Survival.thirstPerPoll)
    if hunger <= 0 || thirst <= 0 {
      hp = max(0, hp - Survival.hpLossPerPollStarved)
    }
  }

  mutating func setResources(hunger: Double, thirst: Double) {
    self.hunger = min(100, max(0, hunger))
    self.thirst = min(100, max(0, thirst))
  }

  mutating func revive() {
    hp = 100
    hunger = 100
    thirst = 100
  }

  mutating func applyDamage(_ amount: Double) {
    hp = max(0, hp - amount)
  }

  // [String: Any] para el contrato WorldProtocol.survivalStats/applySurvival.
  func asDictionary() -> [String: Any] {
    ["hp": hp, "hunger": hunger, "thirst": thirst, "dead": isDead]
  }

  static func fromDictionary(_ dict: [String: Any]) -> Survival? {
    guard let hp = number(dict["hp"]), let hunger = number(dict["hunger"]),
          let thirst = number(dict["thirst"]) else { return nil }
    return Survival(hp: hp, hunger: hunger, thirst: thirst)
  }

  private static func number(_ value: Any?) -> Double? {
    if let n = value as? Double { return n }
    if let i = value as? Int { return Double(i) }
    if let f = value as? Float { return Double(f) }
    return nil
  }
}