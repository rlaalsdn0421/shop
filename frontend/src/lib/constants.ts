// Shop policy constant for now: every order ships for the same flat fee.
// Move to per-product / per-seller data if shipping rules ever differ.
export const SHIPPING_FEE = 3500;

// Used for both the first server fetch and every load-more request, so page
// numbers stay consistent. 20 divides evenly into the 2/4/5 column grids.
export const PRODUCT_PAGE_SIZE = 20;

// Orders at or above this amount ship free. Only the policy text and the product card use it for now;
// applying it to the order total is a separate change.
export const FREE_SHIPPING_THRESHOLD = 50000;
