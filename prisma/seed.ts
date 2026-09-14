import { prisma } from "../src/lib/prisma";

const products = [
  {
    name: "베이직 코튼 티셔츠",
    description: "부드러운 100% 코튼 소재의 데일리 반팔 티셔츠.",
    price: 19000,
    imageUrl: "https://picsum.photos/seed/tshirt/600/600",
    stock: 50,
  },
  {
    name: "와이드 데님 팬츠",
    description: "편안한 핏의 워싱 데님 팬츠.",
    price: 49000,
    imageUrl: "https://picsum.photos/seed/denim/600/600",
    stock: 30,
  },
  {
    name: "오버사이즈 후드 집업",
    description: "가볍고 따뜻한 기모 안감 후드 집업.",
    price: 59000,
    imageUrl: "https://picsum.photos/seed/hoodie/600/600",
    stock: 20,
  },
  {
    name: "캔버스 스니커즈",
    description: "어떤 옷에도 잘 어울리는 기본 캔버스화.",
    price: 39000,
    imageUrl: "https://picsum.photos/seed/sneakers/600/600",
    stock: 40,
  },
  {
    name: "울 니트 스웨터",
    description: "울 혼방 소재의 보온성 좋은 니트.",
    price: 45000,
    imageUrl: "https://picsum.photos/seed/knit/600/600",
    stock: 25,
  },
  {
    name: "레더 크로스백",
    description: "미니멀한 디자인의 인조가죽 크로스백.",
    price: 35000,
    imageUrl: "https://picsum.photos/seed/bag/600/600",
    stock: 15,
  },
];

async function main() {
  for (const p of products) {
    await prisma.product.create({ data: p });
  }
  console.log(`Seeded ${products.length} products`);
}

main()
  .then(() => prisma.$disconnect())
  .catch(async (e) => {
    console.error(e);
    await prisma.$disconnect();
    process.exit(1);
  });
