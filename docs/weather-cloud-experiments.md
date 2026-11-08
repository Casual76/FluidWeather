# Cloud rendering iterations, 2026-11-08

The user accepted the previous soft volumetric-looking individual cloud shapes, but rejected the generated atlas's pasted appearance and full-height placement. The atlas resource was removed. A separate deterministic contour-field prototype (`66ccd1a`, integrated as `54f6517`) passed its synthetic STATIC/upper-half probes but produced visible contour bands. A continuous optical texture experiment removed the bands but still looked artificial. Neither experiment is shipped.

The production painter retains the prior Cumulus illumination/shaded-base/soft-edge language. Its coordinate space is restricted to the upper52% and cloud width is capped by this space's height on landscape tablets and short widgets. Overcast uses overlapping stratified cloud groups; the old stretched Deck wash and its rendering code are removed after direct user feedback that it formed a gray stripe across the screen. Gray ambient light remains a continuous palette gradient and is separate from cloud geometry.

The existing atmosphere policy controls cloud coverage, depth, darkness, diffusion, haze and wind; future fronts can deepen light without starting rain. Present particles still require the shared usable WET evidence. No condition-driven lightning is added. Home and widget use the same evidence adapter and painter.

WeatherScene prepares its painter on Default rather than blocking the UI, rejects obsolete prepared state across context/rain changes and uses a visible/RESUMED wall-clock minute for the solar position. The existing30Hz motion clock remains; reading it for minute calculations no longer causes composition work every frame. STATIC and reduced motion retain their gates.

Historical experiment images/logs remain under `artifacts/quality-2.1.2/` for traceability, not as acceptance evidence. The relevant production direction is the actual final tablet capture and the final production-painter matrix; human visual judgment takes precedence over synthetic probes.
