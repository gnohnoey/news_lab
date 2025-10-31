순서
1. 원천 데이터 수
최근 3~6개월 뉴스 헤드라인 / 요약을 모음
네이버 뉴스 검색 API ==> 결과 : 기사 제목/요약/언론사

http://localhost:8080/api/naver/news?query=트럼프&display=100&sort=date

2. 키워드 후보 추출 (빈도 분석)
명사/고유명사만 뽑아 빈도 상위 키워드 계산
형태소 분석기 Komoran 사용(https://docs.komoran.kr/) ==> 결과 : “많이 언급된 단어 Top N”

http://localhost:8080/api/trends/auto/keywords?query=트럼프&months=3&topN=30&display=100&sort=date&maxRequests=15

========================================
여기서부터 문제 발생
예를 들어 '트럼프'를 키워드로 넣으면 1주에 발행한 기사량이 너무 많아서 최근 3개월은 커녕 최근 2주도 못 추출함. 이거는 어떻게 해야할지 모르겠음,,

3. 트렌드성 평가 (변화량 계산)
주간별 기사 수 변화 or 검색량 변화
(a) 뉴스 API 날짜별 기사 수 (b) 데이터랩 검색 트렌드 API ==> 결과 : “최근 급상승한 키워드 Top N”

http://localhost:8080/api/trends/auto/combined?query=트럼프&months=3&topN=30&display=100&sort=date&maxRequests=15&wNews=0.6&wData=0.4


** 트렌드 지수 계산 방법 **
(뉴스 기사 수 변화 + 검색 관심도 변화)를 주간 단위로 정규화(z-score)한 뒤 가중합한 값
==> 뉴스에서는 “얼마나 자주 등장했는가” + 데이터랩에서는 “얼마나 많이 검색되었는가” 를 같은 단위로 표준화함

  1) 뉴스 계산
    newsSeries{weekStart, count} = 각 주(월~일)마다 ‘해당 키워드가 들어간 기사 개수’
    newsLast = 마지막 주 기사 수
    newsPrev = 직전 주 기사 수
    newsWow = last - prev(전주 대비 증가량)
    newsWowPct = (wow / prev) * 100)전주 대비 증가율(%)
    newsMomentum3 = 최근 3주간 증가분 합
    ==> 최근 몇 주 동안 얼마나 급격히 언급이 늘었는가 측정

  2) 데이터랩 주간별 관심도
    newsSeries(weekStart, ratio (관심도지수)) = “사람들이 얼마나 많이 검색했나”를 수치화
    dataLast = 마지막 주 ratio
    dataPrev = 직전 주 ratio
    dataWow = last - prev(검색량 증가폭)
    dataWowPct = (wow / prev)*100(검색량 증가율(%))
    dataMomentum3 = 최근 3주 상승량 합

  3) z-score 표준화
    z = (값 - 평균) / 표준편차
    각 항목별로 (wow, wow%, momentum) 세 가지의 z-score를 만듦

  4) 뉴스 + 데이터랩 결합 점수
    finalScore = 0.6 * (뉴스 z₁+z₂+z₃) + 0.4 * (데이터랩 z₁+z₂+z₃)
    0.6 = 뉴스 가중치 --> 기사 발행량이 더 객관적인 지표이므로 신뢰도를 더 반영
    0.4 = 데이터랩 가중치
  
  5) 요약
    트렌드 지수 = (뉴스의 급상승성 + 검색의 급상승성)
               = (뉴스 wow + 뉴스 증가율 + 뉴스 3주 모멘텀)
               + (데이터랩 wow + 증가율 + 모멘텀)
               → z-score로 정규화 후 가중 평균


4. 데이터랩 호출 (보정)
3단계에서 걸러진 상위 20~50개 키워드를 넣어서 관심 추이 확인
네이버 데이터랩 API ==> 키워드별 관심도 그래프 (주간)

5. 최종 선정 및 뉴스 매핑
상위 키워드와 관련된 최신 뉴스 다시 매칭
네이버 뉴스 검색 API ==> 결과 : “핫 키워드 + 관련 뉴스 리스트”
