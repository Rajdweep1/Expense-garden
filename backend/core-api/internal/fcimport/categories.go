package fcimport

import "expensegarden/core-api/internal/store"

func cat(id int64, name string, parent int64, nec bool, now int64) store.Category {
	c := store.Category{ID: id, Name: name, IsNecessity: nec, UpdatedAt: now}
	if parent != 0 {
		c.ParentID = &parent
	}
	return c
}

// Categories is his category list: the app's 21 seed rows (AppDatabase.SeedCallback, same ids
// and parents) with his renames and necessity change, then the seven he made in Fortune City.
// All 21 seed rows go to the server, not just the used ones: after a Restore the phone's push
// cursor starts past them, so a seed row the server lacked would never be pushed.
func Categories(now int64) []store.Category {
	return []store.Category{
		cat(1, "Food & Drinks", 0, true, now), // necessity: his call
		cat(2, "Groceries", 0, true, now),
		cat(3, "Transportation", 0, true, now), // was Transport
		cat(4, "Housing", 0, true, now),
		cat(5, "Medical", 0, true, now), // was Health
		cat(6, "Entertainment", 0, false, now),
		cat(7, "Shopping", 0, false, now),
		cat(8, "Personal", 0, false, now),
		cat(9, "Family", 0, true, now),
		cat(10, "Investments", 0, true, now),
		cat(11, "Misc", 0, false, now),
		cat(101, "Restaurants", 1, false, now),
		cat(102, "Delivery", 1, false, now),
		cat(103, "Chai & Snacks", 1, false, now),
		cat(301, "Fuel", 3, true, now),
		cat(302, "Cab & Auto", 3, false, now),
		cat(303, "Metro & Bus", 3, true, now),
		cat(401, "Rent", 4, true, now),
		cat(402, "Utilities", 4, true, now),
		cat(601, "Streaming", 6, false, now),
		cat(602, "Outings", 6, false, now),
		cat(1001, "GYM", 0, true, now),
		cat(1002, "EMI", 0, false, now),
		cat(1003, "bae", 0, false, now),
		cat(1004, "ph recharge", 0, true, now),
		cat(1005, "Electronics", 0, false, now),
		cat(1006, "gift", 0, false, now),
		cat(1007, "Accessories", 0, false, now),
	}
}
