// Shop policy constant for now: every order ships for the same flat fee.
// Move to per-product / per-seller data if shipping rules ever differ.
export const SHIPPING_FEE = 3500;

// Used for both the first server fetch and every load-more request, so page
// numbers stay consistent. 20 divides evenly into the 2/4/5 column grids.
export const PRODUCT_PAGE_SIZE = 20;
