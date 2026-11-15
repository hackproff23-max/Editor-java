package org.jareditor.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

public class HomeActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        l.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setText("Editor Java");
        t.setTextSize(26);
        t.setGravity(Gravity.CENTER);
        l.addView(t, new LinearLayout.LayoutParams(-1, -2));
        Button b1 = new Button(this);
        b1.setText("Editar jogos");
        b1.setTextSize(18);
        Button b2 = new Button(this);
        b2.setText("Editar PES");
        b2.setTextSize(18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (72 * getResources().getDisplayMetrics().density));
        lp.topMargin = pad;
        Button b3 = new Button(this);
        b3.setText("Editor de save");
        b3.setTextSize(18);
        l.addView(b1, lp);
        l.addView(b2, lp);
        l.addView(b3, lp);
        setContentView(l);
        b3.setOnClickListener(v -> startActivity(new Intent(this, SaveActivity.class)));
        b1.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        b2.setOnClickListener(v -> startActivity(new Intent(this, PesActivity.class)));
    }
}
