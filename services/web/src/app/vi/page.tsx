import { BirthForm } from "@/components/BirthForm";
import { Sage } from "@/components/Sage";

export default function ViHome() {
  return (
    <main className="home">
      <section className="tv-hero">
        <span className="vert" aria-hidden="true">
          紫微斗數
        </span>
        <h1 className="h-display">Lá số của bạn, đọc theo sách xưa</h1>
        <p className="lead">
          Nhập ngày giờ sinh. Bàn 12 cung được an sao theo âm lịch Việt, luận giải dựa trên kinh sách cổ.
        </p>
        <Sage mood="greet" className="hero-sage" />
      </section>
      <BirthForm />
    </main>
  );
}
