# AZAS Backend

[운영 Swagger UI](https://api.azas.n-e.kr/docs) · [LAMP 서비스](https://azas-app.vercel.app/)

LAMP(Lunar Access and Mission Planning)는 달 남극 후보지 27곳 중 한 곳과 임무 마감일을 선택하면 착륙 가능한 시간을 찾아주는 서비스입니다. 이 백엔드는 접지 가능 구간, 지구 발사장별 일정, 예비 달 전이 궤적과 착륙 후 하늘 데이터를 제공합니다.

## 구현 범위와 분석 기준

- **접지 가능 구간** — 2027년 1월 1일 UTC부터 사용자가 지정한 마감 시각까지 10분 간격으로 태양과 지구의 고도를 해당 방위각의 지형 지평선과 비교합니다. 기본 조건은 지표 위 1m에서 두 천체가 모두 지평선보다 높은 상태로 48시간 연속 활동할 수 있는 것입니다. `earliestTouchdownUtc`는 이 조건을 만족하는 첫 구간의 시작입니다.
- **발사와 전이** — 첫 접지 시각에서 역산해 지구 발사장별 발사, 달 전이 진입(TLI), 달 도착, 접지 시각을 UTC로 제공합니다. 발사 후보를 선택하면 JPL Horizons의 지구·달 위치를 이용해 TLI부터 달 도착까지의 예비 궤적을 계산합니다.
- **착륙 후 하늘** — 선택한 접지 후보의 활동 시간 동안 태양과 지구의 방위각·고도각과 지형 차폐 여부를 제공합니다. 후보지 상세의 미리보기와 착륙 후 응답은 같은 기준으로 2027~2028년의 UTC 일별 가시율을 평균합니다.

## 데이터 모델 (ERD)

[dbdiagram에서 전체 ERD 보기](https://dbdiagram.io/embed#theme=dark&c=pako%3AeNq9W%2B1T20Ya%2F96Z%2FA87%2FUIyQ32kbdJp5oYZDujVPWoomPTuMh1F2ALUyJIrySTkph0ITsYNdIApJCY1OdMmJenROwcIkLvkS%2F4ca%2F0%2F3LMvsrR68UvK3ZdgSbvP7j4vv%2BdtM2YaXyoZGw38dWAC%2Fe3MWwhlZVueki1FsufzyiXUM2ZY9oypTHw20kM%2BpwybvHV2HyH8YAPv%2F%2BrslpxleFgqOTsV5BwVcXG7sVmBdwmElyv1WtE5KCL8aB1%2BofpBEd%2Bq4e0FhKsL%2BOHjxmYZ78LD5t36QRVNpgeRc6uKdyt0CpDffFU%2FeIkam0V4AKKo%2FnyPENxdQc7BId45dBf62DDVm4ZuwaRDXF5E%2BN9rsPbrY3z3EX61ge8dOrvwslrBWycJOMbXZ94689awXsghWZe1eUu16GkZAy4nJ5J%2FSI4k038hT0MD6WFpdGxsdDw9mUqmk8MT%2FunKDSVTsFVDlyxbtgsWozA2nBpKpv5Ifo5PplL858Tk4ODw8NDwEHn4aCA5Qn55pEzFKmi2QGdw9NOxkeE0mzE2MJ5ODoyQn2mgOjiQjqOUUTTFslVZk6aM7DwjNTGZIn%2BGgcjH5Mcno8lU2j%2FJyOcN0y7oqj0v7GE4dTk5Ppr6dDiVlkZTI5QnY%2BPDI8lPk6mBcfo4mfpTavRzRv%2FPgyOTQ83tpOUpTUG2YpqyqktEsyzFZnQDLyU1i6bUGVW30ZX8tV6k6hlTySm6%2FYWrk2RQxsgqaE42M7OyefZ8X985dEU3bKQXNK0X6Uwzcbl0CWmynlX1mXcsoKLoPQKROcW0QGIxdISh1qz87oWLzZEX3xcWBGZ9VVC8hX9Ycw5OUGNlBW%2B%2FfH3sPN1oLC0434G2HzaKNeTsHeFt%2BFQGdT4E5WSbyhiGCTuVbUUylWnFVPSMIk2bck5ptT9vqJWfhV%2BSKWfVgiXlFGCrBcyzlRnFjGBOo1zED%2FfQ%2BQ%2Fe%2B%2BD9vj6U45xRQeK6LbkyAQ6p06oCEjEMTZH1eELTsmYpjIhlFEzYEGxBJuxDX1qGPhU1c70Ctv76uF5bdFYBEU5q%2BNFCL7FnAhC4uomf3e5FAjMJGjCm4S0Ai6VtXHxGJjo725yLpgIczEqyjWw1B8ov5%2FL2Tf%2FiWWVaBvO6hK7qxvWz567SaaqeBQPmio7QWb%2Ba9Qb1BWTABE6nfu3Hwv0irlKUa0rbefyyiVBl52mNHnPnELGheJsM%2FSfAEv6%2BxvHPubtBkKxHsByuxpKl2hyc%2FG9a20wLAxN0iVISDOtCtF1xifxQJAcjm3614XxfAZAnBjcBVPrOM2losq3ahawiZRVwHMDft7NGgRwnbyoZlfDy7cAeNEOf6XxKcEuDE5dRff8VaFMCOatb8BPhe%2Bu4VAb8L1boIxheqcz1tGBOy6CoAJJzMoVubjUdLcnUlfgh5%2BkhwhXQ2N0FUGnE1JotYUxZiglqI80q6sysLclTxhyYKl%2B4m%2BW4mfE1yHLnXaPVjOuSpRl5IPxVQQYMsNRsd8Tx84qzskBYh2%2FtoQvEGon2bpapFd4CT7vYKG8yxALPfrDQIygWw1aqjMxfuPrzXqT%2B8KOwWUoW9Kb21Hm8BbpfApNAja1NWJV4e8Lbo2JPpIWGlbrXU%2BCQhfosaZYFCJIFyKC1sSVBNeWbaq5gz0p0J8jKyZomDAqer%2B%2Bb9y58%2BGGPMLcrpXaOFvEvr1AfiAPfXwMFplEUAZfa08a9xwnk6sT2ifPdMwhw0A3UlzgPw0XxuCfuzqKooVBNqFQbD54wklOakblGOBWgbUOIwXxHZ2rN7E8hagJg7doDPHZPJagYAVn2inI7RwAyhNu49qSxukaNq7xZf7FCQa263lj9FmwbL0HAWCrDA6o%2Fqzk%2F7xHXA7EuDHJ2v%2BXsIVYPCMTnMlAgExvlEz6LOAKm0DxUFQG%2BiRRd6yWf2crncZme74PVaWxd33%2BKH665MKhL3ehnc5KHmx1PA4nDKl2vxqa9wXptlcPHvGjVAJG8ixpLFfAj4ER2F0CEqH64gE92EH5426mCGbL4haCjs%2FwEkBkcOCIuKOjBC3pmVnDgzRdB%2F6371maujQOAc7SA7%2B8REG7cA9UrVZz9UgKdh9cksCRh0cP1ns6cuQ8gT8FNS4ps2R3CWhvH7ONZMyczCzpjmv8N4VqhAP%2B4cusMxoU8T3wKaF0wpQu9iAore3jOxwMAkCE4CknM6cSnKy7kQjy4D6HE8iMQLlUnmkKDsdaINwQ4AT1M8O8kjwalILqWmhwZ6eHZAKMLqJqTQ%2BkNeGOBAbC8aceihsgsRc%2B2HslgSyIJhzkH3iCn6gVbaZWBkLAdYjZWCAAjKgIouYUCbkmcAxCLkBjrYh87ZU61LIoCBYW4fwXRf5pcvLWHHxB0q9cAxIvVxhIpQoCxFp29Q4Lb9doaZBIJXl8A217DS9%2B6DpZODiyjyFnw9ErU%2BVkBhJOkmQmR13EFv9jClZekDMKiGfhC5IarFbfwcX%2BNL0Y2R8sdjAw5%2FmKPyPy8TNJA6g7jcilXdygkIYgFnKXbeOkYYoStChgcZ%2Bvr48bKGmwH4Z1a%2FdkhpF5Hj6iLLz4ibxurAC3c02dkLVPQGNx667fJg13JMpGyRSgHtk%2FgpAk0mUp%2BNjlMBbNZdJZLJB%2BC3RHescFE6yGkccUOweD9UlMgCmAOiKGDrJ2PbLVZOm5aVrWCqYSrCSwXlmd0w7LVDMQiliXPgDbbyg37ij%2FJzZtg4ToJWHyioQKh0iYa4InZq01trzvLz3kmCxC4U2s8WGEyWnx9PDk%2BQuzd%2BeWJ8x3YR20BZJNo1rPoWCDIK1tNZpZ%2FUwIsQJsZJiCOmVZ11ZptM8i4roPdM4B2TeVFkaD%2FSa0JYndWIH5CjTvVxmItgQbGkpRjNK%2BnVsuwkBgLVdGewGaVG3kVQC%2FWNFcW2GJE%2Bak6UQ33KIPVrTg%2FrST4X2eNAAXg6m1SWCRDVp4gvF%2BG8K2ns3jCY%2F%2B5ZiQBaJy51pxzNYip%2FWFEvgonAIuDEwj%2BLq%2BYqpGVKD22HyAXB7z9qC%2BGTHOoSEh0hb%2FvRz3himcPGh1HZ0MAnJxAqdE0dURoIDUUCZ2%2BMediNtYc7tvY12KpGeIdUmBGk5PJIaoqVIwAZNRHkhjbLSezcnCwiEwKzl5Z1%2FUvHPy52tEyjegdCCxxhKVemLhd8EqocXuFOKeTDaqi%2FpXF4C8LQDMveYelftqtskd8al3QiQ6Coh236CBFnKaZACtjsOOCu9goOjvVxt0TP%2BcYPLGRYqARF874oBgUrUBNgmtv66jDS6I3D53lF3wXlLvMfUIaBpBHyxD7GxAiUNH44t7Qeq0ilzdb7cfbABVe7jQHkS7IWCIrSCbxmHFxMF2JphKI%2B2Eg7tx97AY9ENJRuF%2BG5V4UceVJAvV9c74XOf86ISHDUcUX6rGUqLu1efrym9f%2B0gDF7HLt%2BsFzEofEr82%2Bd7oFwvhr4L%2F0DpaPFDWTLBcr3UGkZNhG%2FCzvalUfu950XkE%2F5XOKkgDmsgBNOCrJ78BcJMJmIwOBoM0iwVmIeqLSQ%2F8cxqZOZgUVk6Q0y%2FCHV2OociSapnjnOV6ugqNebCy6OhwsErpVqQxNu2aUYD3y%2FVCk%2BFVB1kija1qTZ%2Fzh3Sn1EQJI3SvickQjIRArxKFZfzyuer6VeRYOzuHYwe9ZhViLFhoY%2BhMFYsUGkANrv%2BDddYjvqQNknVyvtdtMbag33V3E1XXELNfrfbDWDnGlNMOlOkciM0adNo4PnkPI7PWDeT%2BEaipXZgbTMN%2Fn9h8ScPZHj%2BVW%2FRQKXioV%2FXUQmnGdsTz0ul03so3vFnQt0I0NPIpj%2BeodpOh8ZNsEfcoo6FnZnJea9idZSsbQsy1zdJbJseIm8YvORs15fMK776RhQOrRYIkOwOVqTKk%2Bjku9ARb0Bk8dG0KLZ%2B4PzvNMICzPNnYgFPogrBS8knfnICrI5NxoGlDZKVURR2FuO0xdaXxxvwYP4dsRrJbo5em81UbqQeGbEi07hf42vj%2FejP%2F%2Bm4NOP0kxpb4Y3UjkIXV1nRfNGZxsAyPwwzUa47%2FzuU4KrDQO5Q3HrQUB8yOuK0S8ClavWcOBqL%2FqL%2Bi1blxNDKSTEx8lh4eQU6aVN37f4U2djyuKzo09MKOt0edNI29Y4CTcia1reG4fJmOrc9QrRuf5wTsSbtpmgbu3oq8LfOGreIeLBrYp65ZW0GUTklNyC4mIJCanT48keaUEDK9WIQ1L9hgMBhg92TRVkuvGLDqtmJDGmu2jGxhNNmYAgFqGRhs1ongvhsTrLg0SYEoGCmKY0jVVM2KbW4GlOr9%2Fge9v4N0iQQlSN1pf81%2FI8NfycmCXmqQpc4rWQjOzrouAMMgiFSsvMvIqXgQF4mtrvlP4188qNrgDK1gtK5cgFED1Iwj1FiFY3WkUa4DCdzac%2FSJA6SHpexB3D17mgNz7WMTFE%2B%2FuV09nsVcQnsTwK2pGhPmcC7qMcJHEXy3wQ5sHZCyMYXhGcKSJR2SZLLsCRBA5VFVwb49whyBcfXPdwLgyfUlohSQiLoD0B2%2BFRA26AqqikEPCbmxTzRBHQKmLPfREsO%2FSLy4f%2FBxLNtADPTW6frmeGtHIqCYR9JL94uLBz7HUQ4FLIjbW7I%2FZSuyE2EXjQ4PTOJcXosSpe6sWnxhJNyeyyxihADYwigYjBMJiGqBv5iY7846nH8lzxyOeR9hZ%2FIh4F6ypEW9P14eKjjNid01PCfqgZiE1l67lYmhRPxZFw%2FVYgU7ZF74NCD6oI9cRULiYWyTNlo9XNmQYn%2BCtehbEr%2BFSmbzAlaKvFUbzAL%2FD426QuLlPJkZTvJ9IbkceLkTfLTDylHlTsp2Z7aBd7rrldhosdtNi3H2sPGTLKuToxqJlYhuFzGyWVLjC6hWvKW5HgHXI8VbR%2BYk20Li7TKDLF0mBntwnc%2FvpwOn9f7jFbEjKaEbHO5U0gHQ7trTaW3IpteJ0l7AVc%2BMj5hJmpPX%2BHy5qtMGYCFT9HwBLG0TuyGhFdns229rAI%2B8PegpHMw7QC4R%2FXKE3MW%2FtQYYOpvvisEH%2F40P9%2BARe%2FgpIAAFsqcyrlM7qbefnVxDcciXDlSq5bcbKYgwkKBbcv0NvD7mr8HvRzs52QA990XXzboz4LmTup6SgLb1q9L2ZKC1qU191lTCmFd2yAR3bEu4S0lQ9X7AlS5fz1iy8j3Qo3gE939N8DPs7ESnpSegNf%2FcJ4mrYmEdAmSH1l%2Bazay%2FAlxnFe60bORWEy8NmWE%2FJB2t7XXpXYrSwS1uW5mCQFO%2FQ6SUIeGeY2Wh453YzNjqR9v5%2FEC3tJJq3VagJ0StONIkFuzmq4P1yr9uBZ1375p0Bfg%2FKvRYFNvaiTOvR%2F9lz%2Fv4y3lD8NyvjbCX6TlN8jNn28iVTNw10iFx05UKJNKymeG5EyyVu%2BHx3w292NHxO0YwMif9vxMk%2FbsJ8txNudjghdAM8KMJeQWCxMRroEO1j0IiMahCvJoldqOTg%2BEe9CHjl1Fbhz%2B8sMcGOy2G6zJSamVcogAsReqd7QrFb6mA9TlDk8pudLkCjvfftjz5H24nh9XglIWzu%2FcFdhYaceeu%2FjT3CJw%3D%3D)

## 로컬 실행과 초기 데이터 적재

저장소 루트에서 Docker Compose 2.24 이상으로 Spring Boot(Java 21)와 PostgreSQL 17을 시작합니다.

```powershell
docker compose --env-file docker/postgres.env --file compose.local.yaml up --build --detach --wait --wait-timeout 180
```

기본 설정으로 API는 `127.0.0.1:8000`, DB는 `127.0.0.1:55432`에서 열립니다. 프론트엔드의 로컬 주소를 추가할 때는 `.env.example`을 참고해 `.env`의 `AZAS_CORS_ORIGINS`에 허용할 주소를 JSON 배열로 지정하세요. [로컬 Swagger UI](http://127.0.0.1:8000/docs) · [DB 연결 설정](docs/database.md)

Flyway가 빈 DB에 스키마를 생성하면 사용할 기능에 따라 데이터를 적재합니다.

| 기능 | 새 DB에 필요한 데이터 |
| --- | --- |
| 후보지 조회 | DataGrip에서 지형 데이터셋 → 후보지 → 지평선 표본 순으로 CSV 세 개를 가져온 뒤 `after-import.sql`을 실행합니다. 예상 결과는 데이터셋 1개, 후보지 27개, 지평선 표본 97,200개입니다. [CSV와 적재 순서](docs/terrain-data.md#새-db에-업로드) |
| 접지 시각·가시율 분석 | 별도 원본 `LLSN-Java21-ver2`의 `observer-2027-2029`에서 분석할 후보지의 CSV를 가져옵니다. 파일 이름의 후보지 코드를 `landing_site_id`와 매칭하고, UTC 시각과 태양·지구 관측각을 `observer_sample`에 넣습니다. |
| 발사 일정·궤적 | 같은 원본의 `experiments/launch_sites.csv`를 `launch_site`에 적재합니다. 원본 행 순서가 발사장 ID 1~13에 대응합니다. [컬럼 매핑](docs/database.md#스키마-변경) |

## API 사용 흐름

아래 경로의 공통 접두사는 `/api/v1`입니다. 한 번 접수한 `runId`로 접지 결과부터 궤적까지 조회합니다.

| 단계 | 요청 | 응답에서 사용할 값 |
| --- | --- | --- |
| 후보지 선택 | `GET /landing-sites` · `GET /landing-sites/{id}` | 목록의 `landingSiteId`가 `{id}`입니다. 상세의 `meanDailyVisibility`에서 태양·지구 평균 가시율을 읽습니다. |
| 분석 접수 | `POST /landing-sites/{id}/date-opportunities` | 본문에 `{"missionDeadlineUtc":"2027-01-31"}`처럼 마감일을 보냅니다. HTTP 202 응답의 `data.analysisRunId`가 `{runId}`입니다. |
| 접지 구간 확인 | `GET /analyses/{runId}` · `GET /analyses/{runId}/date-opportunities` | `executionStatus`가 `SUCCEEDED`이면 `windows`와 `earliestTouchdownUtc`를 확인합니다. 만족 구간이 없으면 첫 접지 시각은 `null`입니다. |
| 하늘·타임라인 조회 | `GET /analyses/{runId}/surface-candidates/{candidateId}/post-landing` · `GET /analyses/{runId}/daily-results` | 첫 접지에서 본 하늘은 접지 결과의 `candidates`에서 `proposedLandingUtc`가 첫 접지 시각인 후보의 `candidateId`로 조회합니다. 일별 결과는 `?date=YYYY-MM-DD`로 선택할 수 있습니다. |
| 발사장 선택 | `GET /analyses/{runId}/launch-options` | `launchSites`에서 발사장을 고르고 그 안의 `candidates`에서 `candidateId`와 네 단계의 UTC 일정을 읽습니다. 발사장에 따라 후보가 없을 수 있습니다. |
| 궤적 계산·재조회 | `POST /analyses/{runId}/launch-options/{candidateId}/trajectory` · `GET /analyses/{runId}/trajectories/{trajectoryId}` | 선택한 **발사 후보** ID로 계산한 뒤 `trajectoryRunId`를 저장합니다. 같은 궤적을 다시 열 때는 그 ID로 GET을 호출합니다. |

요청 옵션, 응답 필드, 실패 코드는 [분석 API 가이드](docs/analysis-api.md)에서 확인할 수 있습니다.

## 검증 방법과 알려진 한계

- **로컬 확인** — DB 연결이 정상이면 readiness가 `UP`을 반환합니다. 지형 적재 후 Swagger에서 `GET /api/v1/landing-sites`를 실행하면 후보지 27개를 확인할 수 있습니다. [Readiness](http://127.0.0.1:8000/actuator/health/readiness)
- **자동 테스트** — JDK 21과 Docker가 준비된 환경에서 `./gradlew check bootJar`를 실행합니다. Windows에서는 `.\gradlew.bat check bootJar`를 사용합니다. 통합 테스트는 Testcontainers로 별도의 PostgreSQL을 띄웁니다.
- **가시성 해석** — 접지 분석에 필요한 10분 격자의 관측값이 빠지면 `OBSERVER_DATA_INCOMPLETE`로 실행이 실패합니다. 가시율은 천체 중심이 지형 지평선 위에 있는 시간의 비율이며, 실제 발전량과 통신 품질은 별도 검증이 필요합니다.
- **궤적 해석** — 발사 시각은 가정한 주차 궤도의 기하 조건으로 구한 후보입니다. 전이 궤적은 지구 중심 2체 모델로 달 도착까지 계산하며, 달 궤도 진입과 표면 하강은 모델 범위 밖입니다.
