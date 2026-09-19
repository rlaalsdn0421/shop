ALTER TABLE products ADD COLUMN category VARCHAR(50);

INSERT INTO products (id, name, description, price, image_url, stock, category, created_at)
SELECT gen_random_uuid()::text, v.name, v.description, v.price, v.image_url, v.stock, v.category, now()
FROM (VALUES
    ('수분 진정 크림', '민감성 피부를 위한 저자극 수분 진정 크림.', 28000, 'https://picsum.photos/seed/beauty-cream/600/600', 40, '뷰티'),
    ('톤업 선크림', '백탁 없는 산뜻한 톤업 선크림 SPF50+.', 22000, 'https://picsum.photos/seed/beauty-sun/600/600', 60, '뷰티'),
    ('첼시 부츠', '어디에나 잘 어울리는 클래식 첼시 부츠.', 89000, 'https://picsum.photos/seed/shoes-chelsea/600/600', 20, '신발'),
    ('러닝화', '가볍고 쿠셔닝이 좋은 데일리 러닝화.', 69000, 'https://picsum.photos/seed/shoes-running/600/600', 35, '신발'),
    ('오버핏 맨투맨', '편안한 오버핏 기모 맨투맨.', 32000, 'https://picsum.photos/seed/top-sweatshirt/600/600', 45, '상의'),
    ('스트라이프 셔츠', '캐주얼하게 입기 좋은 스트라이프 셔츠.', 36000, 'https://picsum.photos/seed/top-stripe/600/600', 30, '상의'),
    ('숏패딩', '가볍고 따뜻한 경량 숏패딩.', 99000, 'https://picsum.photos/seed/outer-padding/600/600', 25, '아우터'),
    ('트위드 자켓', '클래식한 무드의 트위드 자켓.', 79000, 'https://picsum.photos/seed/outer-tweed/600/600', 18, '아우터'),
    ('슬랙스', '어디에나 활용하기 좋은 기본 슬랙스.', 42000, 'https://picsum.photos/seed/pants-slacks/600/600', 40, '바지'),
    ('카고 팬츠', '실용적인 포켓 디테일의 카고 팬츠.', 47000, 'https://picsum.photos/seed/pants-cargo/600/600', 32, '바지'),
    ('플리츠 스커트', '우아한 라인의 플리츠 롱스커트.', 39000, 'https://picsum.photos/seed/skirt-pleats/600/600', 22, '원피스/스커트'),
    ('랩 원피스', '여성스러운 실루엣의 랩 원피스.', 55000, 'https://picsum.photos/seed/dress-wrap/600/600', 20, '원피스/스커트'),
    ('토트백', '데일리로 들기 좋은 심플한 토트백.', 62000, 'https://picsum.photos/seed/bag-tote/600/600', 28, '가방'),
    ('백팩', '수납이 넉넉한 캐주얼 백팩.', 58000, 'https://picsum.photos/seed/bag-backpack/600/600', 30, '가방'),
    ('버킷햇', '포인트 주기 좋은 코튼 버킷햇.', 19000, 'https://picsum.photos/seed/hat-bucket/600/600', 50, '모자'),
    ('볼캡', '데일리로 활용하기 좋은 기본 볼캡.', 17000, 'https://picsum.photos/seed/hat-cap/600/600', 55, '모자'),
    ('니트 머플러', '보들보들한 촉감의 니트 머플러.', 24000, 'https://picsum.photos/seed/acc-muffler/600/600', 35, '소품'),
    ('가죽 벨트', '깔끔한 디자인의 가죽 벨트.', 21000, 'https://picsum.photos/seed/acc-belt/600/600', 40, '소품'),
    ('순면 잠옷 세트', '부드러운 순면 소재의 잠옷 세트.', 33000, 'https://picsum.photos/seed/home-pajama/600/600', 25, '속옷/홈웨어'),
    ('브라렛 세트', '편안한 착용감의 브라렛 세트.', 26000, 'https://picsum.photos/seed/home-bralette/600/600', 30, '속옷/홈웨어'),
    ('요가매트', '미끄럼 방지 처리된 요가매트.', 29000, 'https://picsum.photos/seed/sports-yoga/600/600', 38, '스포츠/레저'),
    ('트레이닝 반바지', '통기성 좋은 트레이닝 반바지.', 25000, 'https://picsum.photos/seed/sports-shorts/600/600', 42, '스포츠/레저')
) AS v(name, description, price, image_url, stock, category);
