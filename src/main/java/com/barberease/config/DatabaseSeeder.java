package com.barberease.config;

import com.barberease.models.Admin;
import com.barberease.models.Chair;
import com.barberease.models.Service;
import com.barberease.repositories.AdminRepository;
import com.barberease.repositories.ChairRepository;
import com.barberease.repositories.ServiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ChairRepository chairRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        seedAdmin();
        seedServices();
        seedChairs();
    }

    private void seedAdmin() {
        if (adminRepository.findByUsername("admin").isEmpty()) {
            Admin admin = new Admin();
            admin.setUsername("admin");
            admin.setEmail("admin@barberease.com");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setFullName("System Administrator");
            admin.setRole("super_admin");
            admin.setActive(true);
            adminRepository.save(admin);
            System.out.println("👤 Default admin account seeded (username: admin, password: admin123)");
        }
    }

    private void seedServices() {
        if (serviceRepository.count() == 0) {
            List<Service> services = new ArrayList<>();

            services.add(createService("Haircut",
                    "Professional haircut and styling tailored to your preference.",
                    30, new BigDecimal("25.00"), "haircut"));

            services.add(createService("Shaving",
                    "Classic clean shave or beard trim with hot towel treatment.",
                    20, new BigDecimal("15.00"), "shave"));

            services.add(createService("Both (Haircut & Shaving)",
                    "Complete grooming combo package including precision haircut and clean shave.",
                    45, new BigDecimal("35.00"), "combo"));

            services.add(createService("Facial",
                    "Deep cleansing, exfoliating steam facial and rejuvenating treatment.",
                    30, new BigDecimal("30.00"), "facial"));

            serviceRepository.saveAll(services);
            System.out.println("✂️ Grooming services seeded successfully (" + services.size() + " services created)");
        }
    }

    private void seedChairs() {
        if (chairRepository.count() == 0) {
            List<Chair> chairs = new ArrayList<>();
            Chair chair1 = new Chair();
            chair1.setChairNumber(1);
            chair1.setName("Main Service Chair 1");
            chair1.setStatus("available");
            chair1.setActive(true);
            chairs.add(chair1);

            Chair chair2 = new Chair();
            chair2.setChairNumber(2);
            chair2.setName("Main Service Chair 2");
            chair2.setStatus("available");
            chair2.setActive(true);
            chairs.add(chair2);

            chairRepository.saveAll(chairs);
            System.out.println("💺 2 Main Service Chairs seeded successfully");
        } else {
            // Ensure only 2 main service chairs are active and properly labeled
            List<Chair> allChairs = chairRepository.findAll();
            for (Chair c : allChairs) {
                if (c.getChairNumber() == 1) {
                    c.setName("Main Service Chair 1");
                    c.setActive(true);
                } else if (c.getChairNumber() == 2) {
                    c.setName("Main Service Chair 2");
                    c.setActive(true);
                } else {
                    c.setActive(false);
                }
            }
            chairRepository.saveAll(allChairs);
            System.out.println("💺 Configured exactly 2 Main Service Chairs (Chairs 1 & 2 active)");
        }
    }

    private Service createService(String name, String desc, int duration, BigDecimal price, String cat) {
        Service s = new Service();
        s.setName(name);
        s.setDescription(desc);
        s.setDurationMinutes(duration);
        s.setPrice(price);
        s.setCategory(cat);
        s.setActive(true);
        return s;
    }
}
