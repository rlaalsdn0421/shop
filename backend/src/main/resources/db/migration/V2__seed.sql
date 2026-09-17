INSERT INTO products (id, name, description, price, image_url, stock, created_at)
SELECT gen_random_uuid()::text, v.name, v.description, v.price, v.image_url, v.stock, now()
FROM (VALUES
    ('베이직 코튼 티셔츠', '부드러운 100% 코튼 소재의 데일리 반팔 티셔츠.', 19000, 'https://picsum.photos/seed/tshirt/600/600', 50),
    ('와이드 데님 팬츠', '편안한 핏의 워싱 데님 팬츠.', 49000, 'https://picsum.photos/seed/denim/600/600', 30),
    ('오버사이즈 후드 집업', '가볍고 따뜻한 기모 안감 후드 집업.', 59000, 'https://picsum.photos/seed/hoodie/600/600', 20),
    ('캔버스 스니커즈', '어떤 옷에도 잘 어울리는 기본 캔버스화.', 39000, 'https://picsum.photos/seed/sneakers/600/600', 40),
    ('울 니트 스웨터', '울 혼방 소재의 보온성 좋은 니트.', 45000, 'https://picsum.photos/seed/knit/600/600', 25),
    ('레더 크로스백', '미니멀한 디자인의 인조가죽 크로스백.', 35000, 'https://picsum.photos/seed/bag/600/600', 15)
) AS v(name, description, price, image_url, stock)
WHERE NOT EXISTS (SELECT 1 FROM products);
